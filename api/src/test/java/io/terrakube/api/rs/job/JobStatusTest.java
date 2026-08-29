package io.terrakube.api.rs.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class JobStatusTest {

    private static final Set<JobStatus> TERMINAL = EnumSet.of(
            JobStatus.completed, JobStatus.noChanges, JobStatus.notExecuted,
            JobStatus.rejected, JobStatus.cancelled, JobStatus.failed, JobStatus.unknown);

    @Test
    void terminalStatusesReportTerminal() {
        for (JobStatus status : JobStatus.values()) {
            assertThat(status.isTerminal())
                    .as(status.name())
                    .isEqualTo(TERMINAL.contains(status));
        }
    }
}
