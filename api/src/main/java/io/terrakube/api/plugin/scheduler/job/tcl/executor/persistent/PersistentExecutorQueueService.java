package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Value;
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
    private static final Duration RECONCILE_LOCK_TTL = Duration.ofSeconds(10);

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
        } catch (RuntimeException e) {
            log.warn("Could not check persistent executor wait queue for Job {}: {}", job.getId(), e.getMessage());
            return false;
        }
    }

    public void registerWaiting(Job job) {
        try {
            redisTemplate.opsForZSet().add(WAIT_QUEUE_KEY, String.valueOf(job.getId()), job.getId());
        } catch (RuntimeException e) {
            log.warn("Could not register Job {} in the persistent executor wait queue: {}", job.getId(), e.getMessage());
        }
    }

    public boolean canDispatch(Job job) {
        try {
            ensureWarm();
            Long activeCount = redisTemplate.opsForSet().size(ACTIVE_SLOTS_KEY);
            if (activeCount == null || activeCount >= executorReplicas) {
                return false;
            }
            Set<Object> head = redisTemplate.opsForZSet().range(WAIT_QUEUE_KEY, 0, 0);
            if (head == null || head.isEmpty()) {
                return false;
            }
            return String.valueOf(job.getId()).equals(head.iterator().next());
        } catch (RuntimeException e) {
            log.warn("Could not reach Redis to check persistent executor queue position for Job {}, will retry: {}", job.getId(), e.getMessage());
            return false;
        }
    }

    public void acquireSlot(Job job) {
        try {
            redisTemplate.opsForZSet().remove(WAIT_QUEUE_KEY, String.valueOf(job.getId()));
            redisTemplate.opsForSet().add(ACTIVE_SLOTS_KEY, String.valueOf(job.getId()));
        } catch (RuntimeException e) {
            log.warn("Could not record Job {} as holding a persistent executor slot in Redis: {}", job.getId(), e.getMessage());
        }
        job.setPersistentSlotAcquiredAt(Instant.now());
        jobRepository.save(job);
    }

    public void releaseSlot(Job job) {
        boolean held = job.getPersistentSlotAcquiredAt() != null;
        try {
            redisTemplate.opsForSet().remove(ACTIVE_SLOTS_KEY, String.valueOf(job.getId()));
            redisTemplate.opsForZSet().remove(WAIT_QUEUE_KEY, String.valueOf(job.getId()));
        } catch (RuntimeException e) {
            log.warn("Could not release Job {} from the persistent executor queue/slots in Redis: {}", job.getId(), e.getMessage());
        }
        if (held) {
            job.setPersistentSlotAcquiredAt(null);
            jobRepository.save(job);
        }
        wakeNextInQueue();
    }

    private void ensureWarm() {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(WARM_KEY))) {
            return;
        }
        Boolean acquiredLock = redisTemplate.opsForValue().setIfAbsent(RECONCILE_LOCK_KEY, "1", RECONCILE_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquiredLock)) {
            // Another instance is already reconciling; this cycle's canDispatch caller will
            // see a possibly-stale count and likely fail closed, which is safe - the next
            // 30s cycle will find the warm marker set and proceed normally.
            return;
        }
        try {
            List<Integer> activeJobIds = jobRepository.findIdsWithActivePersistentSlot();
            redisTemplate.delete(ACTIVE_SLOTS_KEY);
            if (!activeJobIds.isEmpty()) {
                String[] members = activeJobIds.stream().map(String::valueOf).toArray(String[]::new);
                redisTemplate.opsForSet().add(ACTIVE_SLOTS_KEY, members);
            }
            redisTemplate.opsForValue().set(WARM_KEY, "1");
        } finally {
            redisTemplate.delete(RECONCILE_LOCK_KEY);
        }
    }

    private void wakeNextInQueue() {
        try {
            Set<Object> head = redisTemplate.opsForZSet().range(WAIT_QUEUE_KEY, 0, 0);
            if (head == null || head.isEmpty()) {
                return;
            }
            int nextJobId = Integer.parseInt((String) head.iterator().next());
            jobRepository.findById(nextJobId).ifPresent(nextJob -> {
                try {
                    scheduleJobService.createJobContextNow(nextJob);
                } catch (SchedulerException e) {
                    log.warn("Could not wake queued Job {} after a persistent executor slot freed up: {}", nextJobId, e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not check persistent executor wait queue for a job to wake: {}", e.getMessage());
        }
    }
}
