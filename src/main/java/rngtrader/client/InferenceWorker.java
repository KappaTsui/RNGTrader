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
    private int[] calibration;
    private final List<PendingSound> prefix = new ArrayList<PendingSound>();
    private boolean initialized;
    private volatile boolean closed;
    volatile boolean sharedReady, timersReady, busy;
    volatile int states, probes;
    volatile long revision;
    volatile RecoveryProgress recovery;

    private static final class PendingSound {
        final int pitch, low, high;
        final boolean free;
        PendingSound(int pitch, int low, int high, boolean free) {
            this.pitch = pitch; this.low = low; this.high = high; this.free = free;
        }
        boolean usable() { return free && low == high && low >= 21 && high <= 39; }
    }

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
            catch (Exception e) {
                if (!initialized) { calibration = null; prefix.clear(); }
                failure.accept(e.getMessage() == null ? e.toString() : e.getMessage());
            }
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
        submit("initialize", () -> {
            if (calibration != null || initialized) throw new IllegalStateException("Calibration already initialized");
            calibration = copy;
            report(new RecoveryProgress("collecting_probes", 0, 3, 0, 0, 0));
            if (copy.length != 7) initializeBuffered(false);
        });
    }
    void sound(int pitch, int low, int high, boolean free) {
        submit("sound", () -> {
            PendingSound sound = new PendingSound(pitch, low, high, free);
            if (!initialized) {
                if (calibration == null) return;
                prefix.add(sound);
                if (!sound.usable()) initializeBuffered(false);
                else if (prefix.size() == 3) initializeBuffered(true);
                else report(new RecoveryProgress("collecting_probes", prefix.size(), 3, 0, 0, 0));
                return;
            }
            observe(sound);
            maybeRecover();
        });
    }
    private void initializeBuffered(boolean lookahead) {
        if (lookahead) {
            RecoveryProbe[] probes = new RecoveryProbe[prefix.size()];
            for (int i = 0; i < probes.length; i++)
                probes[i] = new RecoveryProbe(prefix.get(i).pitch, prefix.get(i).low);
            engine.initialize(calibration, probes, this::report);
        } else {
            long start = System.nanoTime(), used = cpuTime();
            report(new RecoveryProgress("legacy", 0, 1, 0, 0, 0));
            engine.initialize(calibration);
            report(new RecoveryProgress("complete", 1, 1, engine.stateCount(), System.nanoTime() - start,
                used < 0 ? -1 : cpuTime() - used));
        }
        for (PendingSound sound : prefix) observe(sound);
        prefix.clear(); calibration = null; initialized = true;
        maybeRecover();
    }
    private void observe(PendingSound sound) {
        engine.observe(sound.pitch, sound.low, sound.high, sound.free);
        if (sound.free) probes++;
    }
    private void report(RecoveryProgress progress) {
        recovery = progress;
        telemetry.record("entity_recovery", "stage", progress.stage, "completed", progress.completed,
            "total", progress.total, "candidates", progress.candidates,
            "elapsedNanos", progress.elapsedNanos, "cpuNanos", progress.cpuNanos);
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
