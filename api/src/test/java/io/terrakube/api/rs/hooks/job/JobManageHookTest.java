package io.terrakube.api.rs.hooks.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yahoo.elide.annotation.LifeCycleHookBinding;

import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent.PersistentExecutorQueueService;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

public class JobManageHookTest {

    ScheduleJobService scheduleJobService;
    WorkspaceRepository workspaceRepository;
    PersistentExecutorQueueService persistentExecutorQueueService;

    @BeforeEach
    void setup() {
        scheduleJobService = mock(ScheduleJobService.class, new FailUnkownMethod<ScheduleJobService>());
        workspaceRepository = mock(WorkspaceRepository.class, new FailUnkownMethod<WorkspaceRepository>());
        persistentExecutorQueueService = mock(PersistentExecutorQueueService.class, new FailUnkownMethod<PersistentExecutorQueueService>());
        // releaseSlot is called unconditionally on every UPDATE, including by the CREATE test
        // below where it's asserted never() - lenient() so that assertion doesn't need its own stub.
        lenient().doNothing().when(persistentExecutorQueueService).releaseSlot(any());
    }

    private JobManageHook subject() {
        return new JobManageHook(scheduleJobService, workspaceRepository, persistentExecutorQueueService);
    }

    private Job job(JobStatus status) {
        Job job = new Job();
        job.setId(4711);
        job.setStatus(status);
        job.setWorkspace(new Workspace());
        return job;
    }

    @Test
    public void updateToPendingReleasesSlotAndCreatesJobContextNow() throws Exception {
        Job job = job(JobStatus.pending);
        doReturn(new Workspace()).when(workspaceRepository).save(any());
        doNothing().when(scheduleJobService).createJobContextNow(any());

        subject().execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        verify(persistentExecutorQueueService).releaseSlot(job);
        verify(scheduleJobService).createJobContextNow(job);
    }

    @Test
    public void updateToRunningReleasesSlotButSkipsCreateJobContextNow() throws Exception {
        Job job = job(JobStatus.running);
        doReturn(new Workspace()).when(workspaceRepository).save(any());

        subject().execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        verify(persistentExecutorQueueService).releaseSlot(job);
        verify(scheduleJobService, never()).createJobContextNow(any());
    }

    @Test
    public void updateToCancelledReleasesSlotAndDeletesJobContext() throws Exception {
        Job job = job(JobStatus.cancelled);
        doReturn(new Workspace()).when(workspaceRepository).save(any());
        doNothing().when(scheduleJobService).deleteJobContext(job.getId());

        subject().execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        verify(persistentExecutorQueueService).releaseSlot(job);
        verify(scheduleJobService).deleteJobContext(job.getId());
    }

    @Test
    public void createDoesNotReleaseASlot() throws Exception {
        Job job = job(JobStatus.pending);
        doReturn(new Workspace()).when(workspaceRepository).save(any());
        doNothing().when(scheduleJobService).createJobContext(job);

        subject().execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        verify(persistentExecutorQueueService, never()).releaseSlot(any());
    }
}
