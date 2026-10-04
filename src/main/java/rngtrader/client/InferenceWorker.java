package rngtrader.client;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import rngtrader.core.*;

/** All mutable model state is confined to one worker. */
final class InferenceWorker implements AutoCloseable {
    final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RNGTrader inference"); t.setDaemon(true); return t;
    });
    private final RngEngine engine = new RngEngine();
    private final Deque<SampleClock.Sample> samples = new ArrayDeque<SampleClock.Sample>();
    private final Consumer<String> failure;
    private boolean initialized;
    private volatile boolean closed;
    volatile boolean sharedReady, timersReady, busy;
    volatile int states, probes;

    InferenceWorker(Consumer<String> failure) { this.failure = failure; }
    private void submit(Runnable action) {
        if (closed) return;
        executor.execute(() -> {
            if (closed) return;
            try { busy = true; action.run(); states = engine.stateCount(); timersReady = engine.timersReady(); }
            catch (CancellationException ignored) { }
            catch (Exception e) { failure.accept(e.getMessage() == null ? e.toString() : e.getMessage()); }
            finally { busy = false; }
        });
    }
    void initialize(int[] pitches) {
        submit(() -> { engine.initialize(pitches); initialized = true; maybeRecover(); });
    }
    void sound(int pitch, int low, int high, boolean free) {
        submit(() -> {
            if (!initialized) return;
            engine.observe(pitch, low, high, free); if (free) probes++;
            maybeRecover();
        });
    }
    void sample(SampleClock.Sample sample) {
        submit(() -> {
            if (!sample.contiguous) { samples.clear(); sharedReady = false; }
            samples.addLast(sample); while (samples.size() > 9) samples.removeFirst();
            if (sharedReady) {
                try { engine.observeShared(sample.draws); }
                catch (IllegalStateException mismatch) { sharedReady = false; samples.clear(); samples.add(sample); }
            } else maybeRecover();
        });
    }
    void invalidSample() { submit(() -> { sharedReady = false; samples.clear(); }); }
    private void maybeRecover() {
        if (!sharedReady && initialized && engine.timersReady() && samples.size() == 9) {
            int[][] draws = new int[9][]; int i = 0;
            for (SampleClock.Sample s : samples) draws[i++] = s.draws;
            try { engine.recoverShared(draws); sharedReady = true; }
            catch (IllegalStateException mismatch) { samples.removeFirst(); }
        }
    }
    void plan(int known, int[] disabled, int minimum, int maximum, int jitter, Consumer<Integer> result) {
        submit(() -> result.accept(sharedReady && probes >= 45 ? engine.plan(known, disabled, minimum, maximum, jitter) : -1));
    }
    void resume(int size, int[] disabled, int wait, int jitter, int closedTicks, OfferData appended, Runnable complete) {
        submit(() -> {
            engine.resume(size, disabled, wait - jitter, wait + jitter,
                Math.max(40, closedTicks - 2), closedTicks + 2, appended);
            complete.run();
        });
    }
    @Override public void close() { closed = true; executor.shutdownNow(); }
}
