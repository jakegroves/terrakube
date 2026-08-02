package io.terrakube.api.rs.hooks.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobStatusTransitionService;
import io.terrakube.api.rs.workspace.Workspace;

class JobManageHookTest {

    private ScheduleJobService scheduleJobService;
    private WorkspaceRepository workspaceRepository;
    private JobRepository jobRepository;
    private TclService tclService;
    private JobStatusTransitionService jobStatusTransitionService;

    private JobManageHook newHook() {
        scheduleJobService = mock(ScheduleJobService.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        jobRepository = mock(JobRepository.class);
        tclService = mock(TclService.class);
        jobStatusTransitionService = mock(JobStatusTransitionService.class);
        return new JobManageHook(scheduleJobService, workspaceRepository, jobRepository, tclService, jobStatusTransitionService);
    }

    private Job jobWithStatus(JobStatus status) {
        Job job = new Job();
        job.setId(42);
        job.setStatus(status);
        job.setTemplateReference("template-1");
        Workspace workspace = new Workspace();
        workspace.setName("ws-1");
        job.setWorkspace(workspace);
        return job;
    }

    @Test
    void createSetsPlanOnlyFromTclServiceAndSavesJob() throws Exception {
        JobManageHook hook = newHook();
        when(tclService.isTemplatePlanOnly("template-1")).thenReturn(true);

        Job job = jobWithStatus(JobStatus.pending);

        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT,
                job, mock(RequestScope.class), Optional.empty());

        assertThat(job.isPlanOnly()).isTrue();
        verify(jobStatusTransitionService).applyBookkeeping(job);
        verify(jobRepository).save(job);
    }

    @Test
    void updateToRunningSkipsCreateJobContextNow() throws Exception {
        JobManageHook hook = newHook();

        Job job = jobWithStatus(JobStatus.running);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT,
                job, mock(RequestScope.class), Optional.empty());

        verify(jobStatusTransitionService).applyBookkeeping(job);
        verify(jobRepository).save(job);
        verify(scheduleJobService, never()).createJobContextNow(any());
    }

    @Test
    void updateToCancelledDeletesJobContext() throws Exception {
        JobManageHook hook = newHook();

        Job job = jobWithStatus(JobStatus.cancelled);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT,
                job, mock(RequestScope.class), Optional.empty());

        verify(jobStatusTransitionService).applyBookkeeping(job);
        verify(scheduleJobService).deleteJobContext(job.getId());
    }

    @Test
    void updateToPendingCreatesJobContextNow() throws Exception {
        JobManageHook hook = newHook();

        Job job = jobWithStatus(JobStatus.pending);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT,
                job, mock(RequestScope.class), Optional.empty());

        verify(jobStatusTransitionService).applyBookkeeping(job);
        verify(scheduleJobService).createJobContextNow(job);
    }
}
