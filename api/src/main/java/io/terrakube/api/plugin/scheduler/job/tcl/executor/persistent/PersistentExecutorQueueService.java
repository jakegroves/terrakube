package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PersistentExecutorQueueService {

    static final String WAIT_QUEUE_KEY = "persistent-executor-wait-queue";
    static final String ACTIVE_SLOTS_KEY = "persistent-executor-active-slots";
    static final String WARM_KEY = "persistent-executor-warm";
    static final String RECONCILE_LOCK_KEY = "persistent-executor-reconcile-lock";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JobRepository jobRepository;
    private final ScheduleJobService scheduleJobService;
    private final int executorReplicas;

    // Manual all-args constructor because Lombok will not copy @Value (same reason
    // PersistentExecutorService has one).
    public PersistentExecutorQueueService(
            RedisTemplate<String, Object> redisTemplate,
            JobRepository jobRepository,
            ScheduleJobService scheduleJobService,
            @Value("${io.terrakube.executor.replicas}") int executorReplicas) {
        this.redisTemplate = redisTemplate;
        this.jobRepository = jobRepository;
        this.scheduleJobService = scheduleJobService;
        this.executorReplicas = executorReplicas;
    }

    public boolean isRegistered(Job job) {
        try {
            return redisTemplate.opsForZSet().score(WAIT_QUEUE_KEY, String.valueOf(job.getId())) != null;
        } catch (DataAccessException e) {
            log.warn("Could not check persistent executor wait queue for Job {}: {}", job.getId(), e.getMessage());
            return false;
        }
    }

    public void registerWaiting(Job job) {
        try {
            redisTemplate.opsForZSet().add(WAIT_QUEUE_KEY, String.valueOf(job.getId()), job.getId());
        } catch (DataAccessException e) {
            log.warn("Could not register Job {} in the persistent executor wait queue: {}", job.getId(), e.getMessage());
        }
    }

    public boolean canDispatch(Job job) {
        try {
            Long activeCount = redisTemplate.opsForSet().size(ACTIVE_SLOTS_KEY);
            if (activeCount == null || activeCount >= executorReplicas) {
                return false;
            }
            Set<Object> head = redisTemplate.opsForZSet().range(WAIT_QUEUE_KEY, 0, 0);
            if (head == null || head.isEmpty()) {
                return false;
            }
            return String.valueOf(job.getId()).equals(head.iterator().next());
        } catch (DataAccessException e) {
            log.warn("Could not reach Redis to check persistent executor queue position for Job {}, will retry: {}", job.getId(), e.getMessage());
            return false;
        }
    }

    public void acquireSlot(Job job) {
        try {
            redisTemplate.opsForZSet().remove(WAIT_QUEUE_KEY, String.valueOf(job.getId()));
            redisTemplate.opsForSet().add(ACTIVE_SLOTS_KEY, String.valueOf(job.getId()));
        } catch (DataAccessException e) {
            log.warn("Could not record Job {} as holding a persistent executor slot in Redis: {}", job.getId(), e.getMessage());
        }
        job.setPersistentSlotAcquiredAt(java.time.Instant.now());
        jobRepository.save(job);
    }
}
