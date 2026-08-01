package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class PersistentExecutorQueueServiceTest {

    RedisTemplate<String, Object> redisTemplate;
    ZSetOperations<String, Object> zSetOperations;
    SetOperations<String, Object> setOperations;
    ValueOperations<String, Object> valueOperations;
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;

    private static final String WAIT_QUEUE_KEY = "persistent-executor-wait-queue";
    private static final String ACTIVE_SLOTS_KEY = "persistent-executor-active-slots";
    private static final String WARM_KEY = "persistent-executor-warm";
    private static final String RECONCILE_LOCK_KEY = "persistent-executor-reconcile-lock";

    @BeforeEach
    void setup() {
        redisTemplate = mock(RedisTemplate.class, new FailUnkownMethod<RedisTemplate>());
        zSetOperations = mock(ZSetOperations.class, new FailUnkownMethod<ZSetOperations>());
        setOperations = mock(SetOperations.class, new FailUnkownMethod<SetOperations>());
        valueOperations = mock(ValueOperations.class, new FailUnkownMethod<ValueOperations>());
        jobRepository = mock(JobRepository.class, new FailUnkownMethod<JobRepository>());
        scheduleJobService = mock(ScheduleJobService.class, new FailUnkownMethod<ScheduleJobService>());

        doReturn(zSetOperations).when(redisTemplate).opsForZSet();
        doReturn(setOperations).when(redisTemplate).opsForSet();
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        // Warm by default so canDispatch tests in this file don't exercise reconciliation
        // unless they explicitly override it.
        doReturn(true).when(redisTemplate).hasKey(WARM_KEY);
    }

    private PersistentExecutorQueueService subject() {
        return new PersistentExecutorQueueService(redisTemplate, jobRepository, scheduleJobService, 2);
    }

    private Job job(int id) {
        Job job = new Job();
        job.setId(id);
        return job;
    }

    @Test
    public void isRegisteredReturnsFalseWhenNotInWaitQueue() {
        doReturn(null).when(zSetOperations).score(WAIT_QUEUE_KEY, "4711");

        assertFalse(subject().isRegistered(job(4711)));
    }

    @Test
    public void isRegisteredReturnsTrueWhenInWaitQueue() {
        doReturn(4711.0).when(zSetOperations).score(WAIT_QUEUE_KEY, "4711");

        assertTrue(subject().isRegistered(job(4711)));
    }

    @Test
    public void registerWaitingAddsToWaitQueueScoredByJobId() {
        doReturn(true).when(zSetOperations).add(WAIT_QUEUE_KEY, "4711", 4711.0);

        subject().registerWaiting(job(4711));

        verify(zSetOperations).add(WAIT_QUEUE_KEY, "4711", 4711.0);
    }

    @Test
    public void canDispatchReturnsFalseWhenAtCapacity() {
        doReturn(2L).when(setOperations).size(ACTIVE_SLOTS_KEY);

        assertFalse(subject().canDispatch(job(4711)));
    }

    @Test
    public void canDispatchReturnsFalseWhenNotHeadOfQueue() {
        doReturn(0L).when(setOperations).size(ACTIVE_SLOTS_KEY);
        Set<Object> head = new LinkedHashSet<>();
        head.add("4700");
        doReturn(head).when(zSetOperations).range(WAIT_QUEUE_KEY, 0, 0);

        assertFalse(subject().canDispatch(job(4711)));
    }

    @Test
    public void canDispatchReturnsTrueWhenHeadOfQueueWithRoom() {
        doReturn(0L).when(setOperations).size(ACTIVE_SLOTS_KEY);
        Set<Object> head = new LinkedHashSet<>();
        head.add("4711");
        doReturn(head).when(zSetOperations).range(WAIT_QUEUE_KEY, 0, 0);

        assertTrue(subject().canDispatch(job(4711)));
    }

    @Test
    public void canDispatchFailsClosedOnRedisError() {
        doThrow(new RedisConnectionFailureException("boom"))
                .when(setOperations).size(ACTIVE_SLOTS_KEY);

        assertFalse(subject().canDispatch(job(4711)));
    }

    @Test
    public void acquireSlotRemovesFromWaitQueueAddsToActiveSlotsAndPersists() {
        doReturn(0L).when(zSetOperations).remove(WAIT_QUEUE_KEY, "4711");
        doReturn(1L).when(setOperations).add(ACTIVE_SLOTS_KEY, "4711");
        doReturn(job(4711)).when(jobRepository).save(any());

        subject().acquireSlot(job(4711));

        verify(zSetOperations).remove(WAIT_QUEUE_KEY, "4711");
        verify(setOperations).add(ACTIVE_SLOTS_KEY, "4711");
        verify(jobRepository).save(any());
    }

    @Test
    public void canDispatchReconcilesFromDbWhenNotWarm() throws Exception {
        doReturn(false).when(redisTemplate).hasKey(WARM_KEY);
        doReturn(true).when(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration.class));
        doReturn(List.of(100, 200)).when(jobRepository).findIdsWithActivePersistentSlot();
        doReturn(true).when(redisTemplate).delete(ACTIVE_SLOTS_KEY);
        doReturn(2L).when(setOperations).add(ACTIVE_SLOTS_KEY, "100", "200");
        doNothing().when(valueOperations).set(WARM_KEY, "1");
        doReturn(true).when(redisTemplate).delete(RECONCILE_LOCK_KEY);
        doReturn(0L).when(setOperations).size(ACTIVE_SLOTS_KEY);
        Set<Object> head = new LinkedHashSet<>();
        head.add("4711");
        doReturn(head).when(zSetOperations).range(WAIT_QUEUE_KEY, 0, 0);

        assertTrue(subject().canDispatch(job(4711)));

        verify(redisTemplate).delete(ACTIVE_SLOTS_KEY);
        verify(setOperations).add(ACTIVE_SLOTS_KEY, "100", "200");
        verify(valueOperations).set(WARM_KEY, "1");
        verify(redisTemplate).delete(RECONCILE_LOCK_KEY);
    }

    @Test
    public void canDispatchFailsClosedWhenAnotherInstanceIsReconciling() {
        doReturn(false).when(redisTemplate).hasKey(WARM_KEY);
        doReturn(false).when(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration.class));
        doReturn(2L).when(setOperations).size(ACTIVE_SLOTS_KEY);

        assertFalse(subject().canDispatch(job(4711)));

        verify(jobRepository, never()).findIdsWithActivePersistentSlot();
    }

    @Test
    public void releaseSlotClearsRedisAndDbStateAndWakesNextHead() throws SchedulerException {
        Job releasingJob = job(4711);
        releasingJob.setPersistentSlotAcquiredAt(Instant.now());
        doReturn(0L).when(setOperations).remove(ACTIVE_SLOTS_KEY, "4711");
        doReturn(0L).when(zSetOperations).remove(WAIT_QUEUE_KEY, "4711");
        doReturn(releasingJob).when(jobRepository).save(any());
        Set<Object> head = new LinkedHashSet<>();
        head.add("4700");
        doReturn(head).when(zSetOperations).range(WAIT_QUEUE_KEY, 0, 0);
        Job nextJob = job(4700);
        doReturn(Optional.of(nextJob)).when(jobRepository).findById(4700);
        doNothing().when(scheduleJobService).createJobContextNow(any());

        subject().releaseSlot(releasingJob);

        verify(setOperations).remove(ACTIVE_SLOTS_KEY, "4711");
        verify(zSetOperations, times(1)).remove(WAIT_QUEUE_KEY, "4711");
        verify(jobRepository).save(releasingJob);
        assertEquals(null, releasingJob.getPersistentSlotAcquiredAt());
    }

    @Test
    public void releaseSlotIsSafeNoOpForJobThatNeverHeldASlot() throws SchedulerException {
        Job job = job(4711);
        doReturn(0L).when(setOperations).remove(ACTIVE_SLOTS_KEY, "4711");
        doReturn(0L).when(zSetOperations).remove(WAIT_QUEUE_KEY, "4711");
        Set<Object> head = new LinkedHashSet<>();
        doReturn(head).when(zSetOperations).range(WAIT_QUEUE_KEY, 0, 0);

        subject().releaseSlot(job);

        verify(jobRepository, never()).save(any());
        verify(scheduleJobService, never()).createJobContextNow(any());
    }

    @Test
    public void releaseSlotWakesNextHeadViaCreateJobContextNow() throws SchedulerException {
        Job releasingJob = job(4711);
        doReturn(0L).when(setOperations).remove(ACTIVE_SLOTS_KEY, "4711");
        doReturn(0L).when(zSetOperations).remove(WAIT_QUEUE_KEY, "4711");
        Set<Object> head = new LinkedHashSet<>();
        head.add("4700");
        doReturn(head).when(zSetOperations).range(WAIT_QUEUE_KEY, 0, 0);
        Job nextJob = job(4700);
        doReturn(Optional.of(nextJob)).when(jobRepository).findById(4700);
        doNothing().when(scheduleJobService).createJobContextNow(any());

        subject().releaseSlot(releasingJob);

        verify(scheduleJobService).createJobContextNow(nextJob);
    }
}
