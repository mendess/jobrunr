package org.jobrunr.storage.nosql.redis;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.jobs.states.ScheduledState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.*;
import org.jobrunr.storage.StorageProviderUtils.BackgroundJobServers;
import org.jobrunr.storage.nosql.NoSqlStorageProvider;
import org.jobrunr.utils.annotations.Beta;
import org.jobrunr.utils.resilience.RateLimiter;
import redis.clients.jedis.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Long.parseLong;
import static java.time.Instant.now;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.jobrunr.jobs.states.StateName.AWAITING;
import static org.jobrunr.jobs.states.StateName.DELETED;
import static org.jobrunr.jobs.states.StateName.ENQUEUED;
import static org.jobrunr.jobs.states.StateName.FAILED;
import static org.jobrunr.jobs.states.StateName.PROCESSING;
import static org.jobrunr.jobs.states.StateName.SCHEDULED;
import static org.jobrunr.jobs.states.StateName.SUCCEEDED;
import static org.jobrunr.storage.JobRunrMetadata.toId;
import static org.jobrunr.storage.StorageProviderUtils.Metadata;
import static org.jobrunr.storage.StorageProviderUtils.areNewJobs;
import static org.jobrunr.storage.StorageProviderUtils.notAllJobsAreExisting;
import static org.jobrunr.storage.StorageProviderUtils.notAllJobsAreNew;
import static org.jobrunr.storage.StorageProviderUtils.returnConcurrentModifiedJobs;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.backgroundJobServerKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.backgroundJobServersCreatedKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.backgroundJobServersUpdatedKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.jobDetailsKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.jobKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.jobQueueForStateKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.jobVersionKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.metadataKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.metadatasKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.recurringJobKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.recurringJobsKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.scheduledJobsKey;
import static org.jobrunr.storage.nosql.redis.RedisUtilities.toMicroSeconds;
import static org.jobrunr.utils.JobUtils.getJobSignature;
import static org.jobrunr.utils.JobUtils.isNew;
import static org.jobrunr.utils.resilience.RateLimiter.Builder.rateLimit;
import static org.jobrunr.utils.resilience.RateLimiter.SECOND;

@Beta
public class JedisRedisStorageProvider extends AbstractStorageProvider implements NoSqlStorageProvider {

    public static final String JOBRUNR_DEFAULT_PREFIX = "";

    private final JedisPool jedisPool;
    private final String keyPrefix;
    private JobMapper jobMapper;

    public JedisRedisStorageProvider() {
        this(new JedisPool());
    }

    public JedisRedisStorageProvider(JedisPool jedisPool) {
        this(jedisPool, rateLimit().at1Request().per(SECOND));
    }

    public JedisRedisStorageProvider(JedisPool jedisPool, RateLimiter changeListenerNotificationRateLimit) {
        this(jedisPool, JOBRUNR_DEFAULT_PREFIX, changeListenerNotificationRateLimit);
    }

    public JedisRedisStorageProvider(JedisPool jedisPool, String keyPrefix, RateLimiter changeListenerNotificationRateLimit) {
        super(changeListenerNotificationRateLimit);
        this.jedisPool = jedisPool;
        this.keyPrefix = keyPrefix;

        new JedisRedisDBCreator(this, jedisPool, keyPrefix).runMigrations();
    }

    @Override
    public void setJobMapper(JobMapper jobMapper) {
        this.jobMapper = jobMapper;
    }

    @Override
    public void announceBackgroundJobServer(BackgroundJobServerStatus serverStatus) {
        try (final Jedis jedis = getJedis(); final Pipeline p = jedis.pipelined()) {
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_ID, serverStatus.getId().toString());
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_WORKER_POOL_SIZE, String.valueOf(serverStatus.getWorkerPoolSize()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_POLL_INTERVAL_IN_SECONDS, String.valueOf(serverStatus.getPollIntervalInSeconds()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_DELETE_SUCCEEDED_JOBS_AFTER, String.valueOf(serverStatus.getDeleteSucceededJobsAfter()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_DELETE_DELETED_JOBS_AFTER, String.valueOf(serverStatus.getPermanentlyDeleteDeletedJobsAfter()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_FIRST_HEARTBEAT, String.valueOf(serverStatus.getFirstHeartbeat()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_LAST_HEARTBEAT, String.valueOf(serverStatus.getLastHeartbeat()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_IS_RUNNING, String.valueOf(serverStatus.isRunning()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_SYSTEM_TOTAL_MEMORY, String.valueOf(serverStatus.getSystemTotalMemory()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_SYSTEM_FREE_MEMORY, String.valueOf(serverStatus.getSystemFreeMemory()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_SYSTEM_CPU_LOAD, String.valueOf(serverStatus.getSystemCpuLoad()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_PROCESS_MAX_MEMORY, String.valueOf(serverStatus.getProcessMaxMemory()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_PROCESS_FREE_MEMORY, String.valueOf(serverStatus.getProcessFreeMemory()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_PROCESS_ALLOCATED_MEMORY, String.valueOf(serverStatus.getProcessAllocatedMemory()));
            p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_PROCESS_CPU_LOAD, String.valueOf(serverStatus.getProcessCpuLoad()));
            p.zadd(backgroundJobServersCreatedKey(keyPrefix), toMicroSeconds(now()), serverStatus.getId().toString());
            p.zadd(backgroundJobServersUpdatedKey(keyPrefix), toMicroSeconds(now()), serverStatus.getId().toString());
            p.sync();
        }
    }

    @Override
    public boolean signalBackgroundJobServerAlive(BackgroundJobServerStatus serverStatus) {
        try (final Jedis jedis = getJedis()) {
            final Map<String, String> valueMap = jedis.hgetAll(backgroundJobServerKey(keyPrefix, serverStatus));
            if (valueMap.isEmpty())
                throw new ServerTimedOutException(serverStatus, new StorageException("BackgroundJobServer with id " + serverStatus.getId() + " was not found"));
            try (final Pipeline p = jedis.pipelined()) {
                p.watch(backgroundJobServerKey(keyPrefix, serverStatus));
                p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_LAST_HEARTBEAT, String.valueOf(serverStatus.getLastHeartbeat()));
                p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_SYSTEM_FREE_MEMORY, String.valueOf(serverStatus.getSystemFreeMemory()));
                p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_SYSTEM_CPU_LOAD, String.valueOf(serverStatus.getSystemCpuLoad()));
                p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_PROCESS_FREE_MEMORY, String.valueOf(serverStatus.getProcessFreeMemory()));
                p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_PROCESS_ALLOCATED_MEMORY, String.valueOf(serverStatus.getProcessAllocatedMemory()));
                p.hset(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_PROCESS_CPU_LOAD, String.valueOf(serverStatus.getProcessCpuLoad()));
                p.zadd(backgroundJobServersUpdatedKey(keyPrefix), toMicroSeconds(now()), serverStatus.getId().toString());
                final Response<String> isRunningResponse = p.hget(backgroundJobServerKey(keyPrefix, serverStatus), BackgroundJobServers.FIELD_IS_RUNNING);
                p.sync();
                return Boolean.parseBoolean(isRunningResponse.get());
            }
        }
    }

    @Override
    public void signalBackgroundJobServerStopped(BackgroundJobServerStatus serverStatus) {
        try (final Jedis jedis = getJedis(); final Pipeline p = jedis.pipelined()) {
            p.del(backgroundJobServerKey(keyPrefix, serverStatus.getId()));
            p.zrem(backgroundJobServersCreatedKey(keyPrefix), serverStatus.getId().toString());
            p.zrem(backgroundJobServersUpdatedKey(keyPrefix), serverStatus.getId().toString());
            p.sync();
        }
    }

    @Override
    public List<BackgroundJobServerStatus> getBackgroundJobServers() {
        try (final Jedis jedis = getJedis()) {
            return new JedisRedisPipelinedStream<>(jedis.zrange(backgroundJobServersCreatedKey(keyPrefix), 0, Integer.MAX_VALUE), jedis)
                    .mapUsingPipeline((p, id) -> p.hgetAll(backgroundJobServerKey(keyPrefix, id)))
                    .mapAfterSync(Response::get)
                    .map(fieldMap -> new BackgroundJobServerStatus(
                            UUID.fromString(fieldMap.get(BackgroundJobServers.FIELD_ID)),
                            Integer.parseInt(fieldMap.get(BackgroundJobServers.FIELD_WORKER_POOL_SIZE)),
                            Integer.parseInt(fieldMap.get(BackgroundJobServers.FIELD_POLL_INTERVAL_IN_SECONDS)),
                            Duration.parse(fieldMap.get(BackgroundJobServers.FIELD_DELETE_SUCCEEDED_JOBS_AFTER)),
                            Duration.parse(fieldMap.get(BackgroundJobServers.FIELD_DELETE_DELETED_JOBS_AFTER)),
                            Instant.parse(fieldMap.get(BackgroundJobServers.FIELD_FIRST_HEARTBEAT)),
                            Instant.parse(fieldMap.get(BackgroundJobServers.FIELD_LAST_HEARTBEAT)),
                            Boolean.parseBoolean(fieldMap.get(BackgroundJobServers.FIELD_IS_RUNNING)),
                            parseLong(fieldMap.get(BackgroundJobServers.FIELD_SYSTEM_TOTAL_MEMORY)),
                            parseLong(fieldMap.get(BackgroundJobServers.FIELD_SYSTEM_FREE_MEMORY)),
                            Double.parseDouble(fieldMap.get(BackgroundJobServers.FIELD_SYSTEM_CPU_LOAD)),
                            parseLong(fieldMap.get(BackgroundJobServers.FIELD_PROCESS_MAX_MEMORY)),
                            parseLong(fieldMap.get(BackgroundJobServers.FIELD_PROCESS_FREE_MEMORY)),
                            parseLong(fieldMap.get(BackgroundJobServers.FIELD_PROCESS_ALLOCATED_MEMORY)),
                            Double.parseDouble(fieldMap.get(BackgroundJobServers.FIELD_PROCESS_CPU_LOAD))
                    ))
                    .collect(toList());
        }
    }

    @Override
    public UUID getLongestRunningBackgroundJobServerId() {
        try (final Jedis jedis = getJedis()) {
            return jedis.zrange(backgroundJobServersCreatedKey(keyPrefix), 0, 0).stream()
                    .map(UUID::fromString)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No servers available?!"));
        }
    }

    @Override
    public int removeTimedOutBackgroundJobServers(Instant heartbeatOlderThan) {
        try (final Jedis jedis = getJedis()) {
            final Set<String> backgroundjobservers = jedis.zrangeByScore(backgroundJobServersUpdatedKey(keyPrefix), 0, toMicroSeconds(heartbeatOlderThan));
            try (final Pipeline p = jedis.pipelined()) {
                backgroundjobservers.forEach(backgroundJobServerId -> {
                    p.del(backgroundJobServerKey(keyPrefix, backgroundJobServerId));
                    p.zrem(backgroundJobServersCreatedKey(keyPrefix), backgroundJobServerId);
                    p.zrem(backgroundJobServersUpdatedKey(keyPrefix), backgroundJobServerId);
                });
                p.sync();
            }
            return backgroundjobservers.size();
        }
    }

    @Override
    public void saveMetadata(JobRunrMetadata metadata) {
        try (final Jedis jedis = getJedis(); final Pipeline p = jedis.pipelined()) {
            p.hset(metadataKey(keyPrefix, metadata), Metadata.FIELD_ID, metadata.getId());
            p.hset(metadataKey(keyPrefix, metadata), Metadata.FIELD_NAME, metadata.getName());
            p.hset(metadataKey(keyPrefix, metadata), Metadata.FIELD_OWNER, metadata.getOwner());
            p.hset(metadataKey(keyPrefix, metadata), Metadata.FIELD_VALUE, metadata.getValue());
            p.hset(metadataKey(keyPrefix, metadata), Metadata.FIELD_CREATED_AT, String.valueOf(metadata.getCreatedAt()));
            p.hset(metadataKey(keyPrefix, metadata), Metadata.FIELD_UPDATED_AT, String.valueOf(metadata.getUpdatedAt()));
            p.sadd(metadatasKey(keyPrefix), metadataKey(keyPrefix, metadata));
            p.sync();
            notifyMetadataChangeListeners();
        }
    }

    @Override
    public List<JobRunrMetadata> getMetadata(String name) {
        try (final Jedis jedis = getJedis()) {
            return jedis.smembers(metadatasKey(keyPrefix)).stream()
                    .filter(metadataName -> metadataName.startsWith(metadataKey(keyPrefix, name + "-")))
                    .map(jedis::hgetAll)
                    .map(fieldMap -> new JobRunrMetadata(
                            fieldMap.get(Metadata.FIELD_NAME),
                            fieldMap.get(Metadata.FIELD_OWNER),
                            fieldMap.get(Metadata.FIELD_VALUE),
                            Instant.parse(fieldMap.get(Metadata.FIELD_CREATED_AT)),
                            Instant.parse(fieldMap.get(Metadata.FIELD_UPDATED_AT))
                    ))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public JobRunrMetadata getMetadata(String name, String owner) {
        try (final Jedis jedis = getJedis()) {
            Map<String, String> fieldMap = jedis.hgetAll(metadataKey(keyPrefix, toId(name, owner)));
            return new JobRunrMetadata(
                    fieldMap.get(Metadata.FIELD_NAME),
                    fieldMap.get(Metadata.FIELD_OWNER),
                    fieldMap.get(Metadata.FIELD_VALUE),
                    Instant.parse(fieldMap.get(Metadata.FIELD_CREATED_AT)),
                    Instant.parse(fieldMap.get(Metadata.FIELD_UPDATED_AT))
            );
        }
    }

    @Override
    public void deleteMetadata(String name) {
        try (final Jedis jedis = getJedis()) {
            List<String> metadataToDelete = jedis.smembers(metadatasKey(keyPrefix)).stream()
                    .filter(metadataName -> metadataName.startsWith(metadataKey(keyPrefix, name + "-")))
                    .collect(toList());

            if (!metadataToDelete.isEmpty()) {
                try (final Pipeline p = jedis.pipelined()) {
                    metadataToDelete.forEach(metadataName -> {
                        p.hdel(metadataName);
                        p.srem(metadatasKey(keyPrefix), metadataName);
                    });
                    p.sync();
                }
                notifyMetadataChangeListeners();
            }
        }
    }

    @Override
    public Job save(Job jobToSave) {
        try (final Jedis jedis = getJedis()) {
            if (isNew(jobToSave)) {
                insertJob(jobToSave, jedis);
            } else {
                updateJob(jobToSave, jedis);
            }
            notifyJobStatsOnChangeListeners();
        }
        return jobToSave;
    }

    @Override
    public int deletePermanently(UUID id) {
        Job job = getJobById(id);
        try (final Jedis jedis = getJedis(); Transaction transaction = jedis.multi()) {
            transaction.del(jobKey(keyPrefix, job));
            transaction.del(jobVersionKey(keyPrefix, job));
            deleteJobMetadata(transaction, job);
            final List<Object> result = transaction.exec();
            int amount = result == null || result.isEmpty() ? 0 : 1;
            notifyJobStatsOnChangeListenersIf(amount > 0);
            return amount;
        }
    }

    @Override
    public Job getJobById(UUID id) {
        try (final Jedis jedis = getJedis()) {
            final String serializedJobAsString = jedis.get(jobKey(keyPrefix, id));
            if (serializedJobAsString == null) throw new JobNotFoundException(id);

            return jobMapper.deserializeJob(serializedJobAsString);
        }
    }

    @Override
    public List<Job> save(List<Job> jobs) {
        if (jobs.isEmpty()) return jobs;

        if (areNewJobs(jobs)) {
            if (notAllJobsAreNew(jobs)) {
                throw new IllegalArgumentException("All jobs must be either new (with id == null) or existing (with id != null)");
            }
            try (final Jedis jedis = getJedis(); Transaction p = jedis.multi()) {
                jobs.forEach(jobToSave -> saveJob(p, jobToSave));
                p.exec();
            }
        } else {
            if (notAllJobsAreExisting(jobs)) {
                throw new IllegalArgumentException("All jobs must be either new (with id == null) or existing (with id != null)");
            }
            try (final Jedis jedis = getJedis()) {
                final List<Job> concurrentModifiedJobs = returnConcurrentModifiedJobs(jobs, job -> updateJob(job, jedis));
                if (!concurrentModifiedJobs.isEmpty()) {
                    throw new ConcurrentJobModificationException(concurrentModifiedJobs);
                }
            }
        }
        notifyJobStatsOnChangeListenersIf(!jobs.isEmpty());
        return jobs;
    }

    @Override
    public List<Job> getJobs(StateName state, Instant updatedBefore, PageRequest pageRequest) {
        try (final Jedis jedis = getJedis()) {
            Set<String> jobsByState;
            if ("updatedAt:ASC".equals(pageRequest.getOrder())) {
                jobsByState = jedis.zrangeByScore(jobQueueForStateKey(keyPrefix, state), 0, toMicroSeconds(updatedBefore), (int) pageRequest.getOffset(), pageRequest.getLimit());
            } else if ("updatedAt:DESC".equals(pageRequest.getOrder())) {
                jobsByState = jedis.zrevrangeByScore(jobQueueForStateKey(keyPrefix, state), toMicroSeconds(updatedBefore), 0, (int) pageRequest.getOffset(), pageRequest.getLimit());
            } else {
                throw new IllegalArgumentException("Unsupported sorting: " + pageRequest.getOrder());
            }
            return new JedisRedisPipelinedStream<>(jobsByState, jedis)
                    .mapUsingPipeline((p, id) -> p.get(jobKey(keyPrefix, id)))
                    .mapAfterSync(Response::get)
                    .map(jobMapper::deserializeJob)
                    .collect(toList());
        }
    }

    @Override
    public List<Job> getScheduledJobs(Instant scheduledBefore, PageRequest pageRequest) {
        try (final Jedis jedis = getJedis()) {
            return new JedisRedisPipelinedStream<>(jedis.zrangeByScore(scheduledJobsKey(keyPrefix), 0, toMicroSeconds(now()), (int) pageRequest.getOffset(), pageRequest.getLimit()), jedis)
                    .mapUsingPipeline((p, id) -> p.get(jobKey(keyPrefix, id)))
                    .mapAfterSync(Response::get)
                    .map(jobMapper::deserializeJob)
                    .collect(toList());
        }
    }

    @Override
    public List<Job> getJobs(StateName state, PageRequest pageRequest) {
        try (final Jedis jedis = getJedis()) {
            Set<String> jobsByState;
            // we only support what is used by frontend
            if ("updatedAt:ASC".equals(pageRequest.getOrder())) {
                jobsByState = jedis.zrange(jobQueueForStateKey(keyPrefix, state), pageRequest.getOffset(), pageRequest.getOffset() + pageRequest.getLimit() - 1);
            } else if ("updatedAt:DESC".equals(pageRequest.getOrder())) {
                jobsByState = jedis.zrevrange(jobQueueForStateKey(keyPrefix, state), pageRequest.getOffset(), pageRequest.getOffset() + pageRequest.getLimit() - 1);
            } else {
                throw new IllegalArgumentException("Unsupported sorting: " + pageRequest.getOrder());
            }
            return new JedisRedisPipelinedStream<>(jobsByState, jedis)
                    .mapUsingPipeline((p, id) -> p.get(jobKey(keyPrefix, id)))
                    .mapAfterSync(Response::get)
                    .map(jobMapper::deserializeJob)
                    .collect(toList());
        }
    }

    @Override
    public Page<Job> getJobPage(StateName state, PageRequest pageRequest) {
        try (final Jedis jedis = getJedis()) {
            long count = jedis.zcount(jobQueueForStateKey(keyPrefix, state), 0, Long.MAX_VALUE);
            if (count > 0) {
                List<Job> jobs = getJobs(state, pageRequest);
                return new Page<>(count, jobs, pageRequest);
            }
            return new Page<>(0, new ArrayList<>(), pageRequest);
        }
    }

    @Override
    public int deleteJobsPermanently(StateName state, Instant updatedBefore) {
        int amount = 0;
        try (final Jedis jedis = getJedis()) {
            Set<String> zrangeToInspect = jedis.zrange(jobQueueForStateKey(keyPrefix, state), 0, 1000);
            outerloop:
            while (!zrangeToInspect.isEmpty()) {
                for (String id : zrangeToInspect) {
                    final Job job = getJobById(UUID.fromString(id));
                    if (job.getUpdatedAt().isAfter(updatedBefore)) break outerloop;

                    try (Transaction transaction = jedis.multi()) {
                        transaction.del(jobKey(keyPrefix, job));
                        transaction.del(jobVersionKey(keyPrefix, job));
                        deleteJobMetadata(transaction, job);

                        final List<Object> exec = transaction.exec();
                        if (exec != null && !exec.isEmpty()) amount++;
                    }
                }
                zrangeToInspect = jedis.zrange(jobQueueForStateKey(keyPrefix, state), 0, 1000);
            }
        }
        notifyJobStatsOnChangeListenersIf(amount > 0);
        return amount;
    }

    @Override
    public Set<String> getDistinctJobSignatures(StateName... states) {
        try (final Jedis jedis = getJedis(); Pipeline p = jedis.pipelined()) {
            List<Response<Set<String>>> jobSignatures = stream(states)
                    .map(stateName -> p.smembers(jobDetailsKey(keyPrefix, stateName)))
                    .collect(toList());
            p.sync();
            return jobSignatures.stream().flatMap(res -> res.get().stream()).collect(toSet());
        }
    }

    @Override
    public boolean exists(JobDetails jobDetails, StateName... states) {
        try (final Jedis jedis = getJedis(); Pipeline p = jedis.pipelined()) {
            List<Response<Boolean>> existsJob = stream(states)
                    .map(stateName -> p.sismember(jobDetailsKey(keyPrefix, stateName), getJobSignature(jobDetails)))
                    .collect(toList());
            p.sync();
            return existsJob.stream().map(Response::get).filter(b -> b).findAny().orElse(false);
        }
    }

    @Override
    public boolean recurringJobExists(String recurringJobId, StateName... states) {
        try (final Jedis jedis = getJedis(); Pipeline p = jedis.pipelined()) {
            List<Response<Boolean>> existsJob = stream(states)
                    .map(stateName -> p.sismember(recurringJobKey(keyPrefix, stateName), recurringJobId))
                    .collect(toList());
            p.sync();
            return existsJob.stream().map(Response::get).filter(b -> b).findAny().orElse(false);
        }
    }

    @Override
    public RecurringJob saveRecurringJob(RecurringJob recurringJob) {
        try (final Jedis jedis = getJedis(); Pipeline p = jedis.pipelined()) {
            p.set(recurringJobKey(keyPrefix, recurringJob.getId()), jobMapper.serializeRecurringJob(recurringJob));
            p.sadd(recurringJobsKey(keyPrefix), recurringJob.getId());
            p.sync();
        }
        return recurringJob;
    }

    @Override
    public List<RecurringJob> getRecurringJobs() {
        try (final Jedis jedis = getJedis()) {
            return jedis.smembers(recurringJobsKey(keyPrefix))
                    .stream()
                    .map(id -> jedis.get(recurringJobKey(keyPrefix, id)))
                    .map(jobMapper::deserializeRecurringJob)
                    .collect(toList());
        }
    }

    @Override
    public int deleteRecurringJob(String id) {
        try (final Jedis jedis = getJedis(); final Transaction transaction = jedis.multi()) {
            transaction.del(recurringJobKey(keyPrefix, id));
            transaction.srem(recurringJobsKey(keyPrefix), id);

            final List<Object> exec = transaction.exec();
            return (exec != null && !exec.isEmpty()) ? 1 : 0;
        }
    }

    @Override
    public JobStats getJobStats() {
        Instant instant = Instant.now();
        try (final Jedis jedis = getJedis(); final Pipeline p = jedis.pipelined()) {
            final Response<String> totalAmountSucceeded = p.hget(metadataKey(keyPrefix, Metadata.STATS_ID), Metadata.FIELD_VALUE);

            final Response<Long> waitingResponse = p.zcount(jobQueueForStateKey(keyPrefix, AWAITING), 0, Long.MAX_VALUE);
            final Response<Long> scheduledResponse = p.zcount(jobQueueForStateKey(keyPrefix, SCHEDULED), 0, Long.MAX_VALUE);
            final Response<Long> enqueuedResponse = p.zcount(jobQueueForStateKey(keyPrefix, ENQUEUED), 0, Long.MAX_VALUE);
            final Response<Long> processingResponse = p.zcount(jobQueueForStateKey(keyPrefix, PROCESSING), 0, Long.MAX_VALUE);
            final Response<Long> succeededResponse = p.zcount(jobQueueForStateKey(keyPrefix, SUCCEEDED), 0, Long.MAX_VALUE);
            final Response<Long> failedResponse = p.zcount(jobQueueForStateKey(keyPrefix, FAILED), 0, Long.MAX_VALUE);
            final Response<Long> deletedResponse = p.zcount(jobQueueForStateKey(keyPrefix, DELETED), 0, Long.MAX_VALUE);

            final Response<Long> recurringJobsResponse = p.scard(recurringJobsKey(keyPrefix));
            final Response<Long> backgroundJobServerResponse = p.zcount(backgroundJobServersUpdatedKey(keyPrefix), 0, Long.MAX_VALUE);

            p.sync();

            final Long awaitingCount = waitingResponse.get();
            final Long scheduledCount = scheduledResponse.get();
            final Long enqueuedCount = enqueuedResponse.get();
            final Long processingCount = processingResponse.get();
            final Long succeededCount = succeededResponse.get();
            final Long allTimeSucceededCount = parseLong(totalAmountSucceeded.get() != null ? totalAmountSucceeded.get() : "0");
            final Long failedCount = failedResponse.get();
            final Long deletedCount = deletedResponse.get();
            final Long total = scheduledCount + enqueuedCount + processingCount + succeededResponse.get() + failedCount;
            final Long recurringJobsCount = recurringJobsResponse.get();
            final Long backgroundJobServerCount = backgroundJobServerResponse.get();
            return new JobStats(
                    instant,
                    total,
                    awaitingCount,
                    scheduledCount,
                    enqueuedCount,
                    processingCount,
                    failedCount,
                    succeededCount,
                    allTimeSucceededCount,
                    deletedCount,
                    recurringJobsCount.intValue(),
                    backgroundJobServerCount.intValue()
            );
        }
    }

    @Override
    public void publishTotalAmountOfSucceededJobs(int amount) {
        try (final Jedis jedis = getJedis()) {
            jedis.hincrBy(metadataKey(keyPrefix, Metadata.STATS_ID), Metadata.FIELD_VALUE, amount);
        }
    }

    protected Jedis getJedis() {
        return jedisPool.getResource();
    }

    private void insertJob(Job jobToSave, Jedis jedis) {
        if (jedis.exists(jobKey(keyPrefix, jobToSave))) throw new ConcurrentJobModificationException(jobToSave);
        try (Transaction transaction = jedis.multi()) {
            saveJob(transaction, jobToSave);
            transaction.exec();
        }
    }

    private void updateJob(Job jobToSave, Jedis jedis) {
        jedis.watch(jobVersionKey(keyPrefix, jobToSave));
        final int version = Integer.parseInt(jedis.get(jobVersionKey(keyPrefix, jobToSave)));
        if (version != jobToSave.getVersion()) throw new ConcurrentJobModificationException(jobToSave);
        try (Transaction transaction = jedis.multi()) {
            saveJob(transaction, jobToSave);
            List<Object> result = transaction.exec();
            jedis.unwatch();
            if (result == null || result.isEmpty()) throw new ConcurrentJobModificationException(jobToSave);
        }
    }

    private void saveJob(Transaction transaction, Job jobToSave) {
        deleteJobMetadataForUpdate(transaction, jobToSave);
        transaction.set(jobVersionKey(keyPrefix, jobToSave), String.valueOf(jobToSave.increaseVersion()));
        transaction.set(jobKey(keyPrefix, jobToSave), jobMapper.serializeJob(jobToSave));
        transaction.zadd(jobQueueForStateKey(keyPrefix, jobToSave.getState()), toMicroSeconds(jobToSave.getUpdatedAt()), jobToSave.getId().toString());
        transaction.sadd(jobDetailsKey(keyPrefix, jobToSave.getState()), getJobSignature(jobToSave.getJobDetails()));
        if (SCHEDULED.equals(jobToSave.getState())) {
            transaction.zadd(scheduledJobsKey(keyPrefix), toMicroSeconds(((ScheduledState) jobToSave.getJobState()).getScheduledAt()), jobToSave.getId().toString());
        }
        jobToSave.getJobStatesOfType(ScheduledState.class).findFirst().map(ScheduledState::getRecurringJobId).ifPresent(recurringJobId -> transaction.sadd(recurringJobKey(keyPrefix, jobToSave.getState()), recurringJobId));
    }

    private void deleteJobMetadataForUpdate(Transaction transaction, Job job) {
        String id = job.getId().toString();
        transaction.zrem(scheduledJobsKey(keyPrefix), id);
        Stream.of(StateName.values()).forEach(stateName -> transaction.zrem(jobQueueForStateKey(keyPrefix, stateName), id));
        Stream.of(StateName.values()).filter(stateName -> !SCHEDULED.equals(stateName)).forEach(stateName -> transaction.srem(jobDetailsKey(keyPrefix, stateName), getJobSignature(job.getJobDetails())));
        if ((job.hasState(ENQUEUED) && job.getJobStates().size() >= 2 && job.getJobState(-2) instanceof ScheduledState)
                || (job.hasState(DELETED) && job.getJobStates().size() >= 2 && job.getJobState(-2) instanceof ScheduledState)) {
            transaction.srem(jobDetailsKey(keyPrefix, SCHEDULED), getJobSignature(job.getJobDetails()));
        }
        job.getJobStatesOfType(ScheduledState.class).findFirst().map(ScheduledState::getRecurringJobId).ifPresent(recurringJobId -> Stream.of(StateName.values()).forEach(stateName -> transaction.srem(recurringJobKey(keyPrefix, stateName), recurringJobId)));
    }

    private void deleteJobMetadata(Transaction transaction, Job job) {
        String id = job.getId().toString();
        transaction.zrem(scheduledJobsKey(keyPrefix), id);
        Stream.of(StateName.values()).forEach(stateName -> transaction.zrem(jobQueueForStateKey(keyPrefix, stateName), id));
        Stream.of(StateName.values()).forEach(stateName -> transaction.srem(jobDetailsKey(keyPrefix, stateName), getJobSignature(job.getJobDetails())));
    }
}