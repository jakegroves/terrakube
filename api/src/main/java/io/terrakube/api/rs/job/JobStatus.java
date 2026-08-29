package io.terrakube.api.rs.job;

public enum JobStatus {
    pending,
    waitingApproval,
    approved,
    queue,
    running,
    completed,
    noChanges,
    notExecuted,
    rejected,
    cancelled,
    failed,
    unknown,
    NeverExecuted;

    /**
     * Whether this is a run-ending state. Drives {@code terrakube.run.finished} /
     * {@code terrakube.run.duration}. Distinct from {@code StreamingService}'s narrower
     * private "stop streaming" check ({@code completed}/{@code failed}/{@code cancelled}).
     * {@code NeverExecuted} is a workspace {@code lastJobStatus} sentinel, not a real job
     * state, so it is not terminal here.
     */
    public boolean isTerminal() {
        return switch (this) {
            case completed, noChanges, notExecuted, rejected, cancelled, failed, unknown -> true;
            default -> false;
        };
    }
}
