package rngtrader.core;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;

/** Cooperative 5 ms computation slices separated by up to 5 ms of rest. */
public final class ComputeBudget {
    private static final ThreadLocal<long[]> SLICE = new ThreadLocal<long[]>();
    private ComputeBudget() { }
    public static void enable() { SLICE.set(new long[] {System.nanoTime()}); }
    public static void clear() { SLICE.remove(); }
    static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Session cancelled");
        long[] slice = SLICE.get(); if (slice == null) return;
        long now = System.nanoTime();
        if (now - slice[0] >= 5_000_000L) {
            long remaining = 10_000_000L - (now - slice[0]);
            if (remaining > 0) LockSupport.parkNanos(remaining);
            slice[0] = System.nanoTime();
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Session cancelled");
        }
    }
}
