package rngtrader.client;

import java.util.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import rngtrader.core.*;

/** Mutable inference state and observation-stage decisions belong to this worker. */
final class InferenceWorker implements AutoCloseable {
    static final class Forecast {
        final int delay; final long revision;
        Forecast(int delay, long revision) { this.delay = delay; this.revision = revision; }
    }
    interface Telemetry { void record(String event, Object... values); }
    final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RNGTrader inference"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t;
    });
    private final RngEngine engine = new RngEngine();
    private final Deque<SampleClock.Sample> samples = new ArrayDeque<SampleClock.Sample>();
    private final Consumer<String> failure;
    private final Telemetry telemetry;
    private final ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
    private final AtomicInteger pending = new AtomicInteger();
    private boolean initialized;
    private volatile boolean closed;
    volatile boolean sharedReady, timersReady, busy;
    volatile int states, probes;
    volatile long revision;

    InferenceWorker(Consumer<String> failure) { this(failure, (event, values) -> { }); }
    InferenceWorker(Consumer<String> failure, Telemetry telemetry) { this.failure = failure; this.telemetry = telemetry; }
    int pending() { return pending.get(); }
    private long cpuTime() { return cpu.isCurrentThreadCpuTimeSupported() ? cpu.getCurrentThreadCpuTime() : -1; }
    private void submit(String name, Runnable action) {
        if (closed) return;
        final long queued = System.nanoTime(); pending.incrementAndGet();
        try { executor.execute(() -> {
            if (closed) { pending.decrementAndGet(); return; }
            long start = System.nanoTime(), used = cpuTime();
            try {
                busy = true; ComputeBudget.enable(); action.run();
                states = engine.stateCount(); timersReady = engine.timersReady(); revision = engine.revision();
            } catch (CancellationException ignored) { }
            catch (Exception e) { failure.accept(e.getMessage() == null ? e.toString() : e.getMessage()); }
            finally {
                ComputeBudget.clear(); busy = false; pending.decrementAndGet();
                telemetry.record("worker", "operation", name, "queuedNanos", start - queued,
                    "elapsedNanos", System.nanoTime() - start, "cpuNanos", used < 0 ? -1 : cpuTime() - used,
                    "pending", pending.get(), "states", states, "revision", revision);
            }
        }); } catch (RejectedExecutionException stopped) { pending.decrementAndGet(); if (!closed) throw stopped; }
    }
    void initialize(int[] pitches) {
        final int[] copy = pitches.clone();
        submit("initialize", () -> { engine.initialize(copy); initialized = true; maybeRecover(); });
    }
    void sound(int pitch, int low, int high, boolean free) {
        submit("sound", () -> {
            if (!initialized) return;
            engine.observe(pitch, low, high, free); if (free) probes++;
            maybeRecover();
        });
    }
    void sample(SampleClock.Sample sample) {
        submit("sample", () -> {
            if (!sample.contiguous) { samples.clear(); sharedReady = false; }
            samples.addLast(sample); while (samples.size() > 9) samples.removeFirst();
            if (sharedReady) {
                try { engine.observeShared(sample.draws); }
                catch (IllegalStateException mismatch) { sharedReady = false; samples.clear(); samples.add(sample); }
            } else maybeRecover();
        });
    }
    void invalidSample() { submit("invalidate_shared", () -> { sharedReady = false; samples.clear(); }); }
    private void maybeRecover() {
        if (!sharedReady && initialized && engine.timersReady() && samples.size() == 9) {
            int[][] draws = new int[9][]; int i = 0;
            for (SampleClock.Sample s : samples) draws[i++] = s.draws;
            try { engine.recoverShared(draws); sharedReady = true; }
            catch (IllegalStateException mismatch) { samples.removeFirst(); }
        }
    }
    void plan(int known, int[] disabled, int minimum, int maximum, int jitter, Consumer<Forecast> result) {
        final int[] stock = disabled.clone();
        submit("plan", () -> {
            if (pending.get() > 1) { result.accept(new Forecast(-1, engine.revision())); return; }
            result.accept(new Forecast(sharedReady && probes >= 45 ? engine.plan(known, stock, minimum, maximum, jitter) : -1, engine.revision()));
        });
    }
    void resume(int size, int[] disabled, long anchor, TickInterval close, long opened, OfferData appended, Runnable complete) {
        final int[] stock = disabled.clone();
        submit("resume", () -> { engine.resumeAt(size, stock, anchor, close, opened, appended); complete.run(); });
    }
    @Override public void close() { closed = true; executor.shutdownNow(); }
}
