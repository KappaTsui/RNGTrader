package rngtrader.core;

/** Cumulative recovery costs and the completed branch count within a stage. */
public final class RecoveryProgress {
    public final String stage;
    public final int completed, total, candidates;
    public final long elapsedNanos, cpuNanos;

    public RecoveryProgress(String stage, int completed, int total, int candidates, long elapsedNanos, long cpuNanos) {
        this.stage = stage;
        this.completed = completed;
        this.total = total;
        this.candidates = candidates;
        this.elapsedNanos = elapsedNanos;
        this.cpuNanos = cpuNanos;
    }
}
