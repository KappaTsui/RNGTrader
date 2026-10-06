package rngtrader.client;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import rngtrader.core.*;
import static org.junit.Assert.*;

public class InferenceRecoveryTest {
    private static void flush(InferenceWorker worker) throws Exception {
        worker.executor.submit(() -> { }).get(180, TimeUnit.SECONDS);
    }
    private static void sound(InferenceWorker worker, RecoveryProbe probe) {
        worker.sound(probe.pitch, probe.ticks, probe.ticks, true);
    }
    private static List<SampleClock.Sample> samples() {
        Random rng = new Random(2371); SampleClock clock = new SampleClock();
        List<SampleClock.Sample> result = new ArrayList<SampleClock.Sample>();
        for (int i = 0; i < 10; i++) {
            List<Integer> order = new ArrayList<Integer>(); for (int n = 0; n < 12; n++) order.add(n);
            Collections.shuffle(order, rng);
            int[] permutation = order.stream().mapToInt(Integer::intValue).toArray();
            SampleClock.Sample sample = clock.accept(ShuffleRng.decode(permutation), i * 5_000_000_000L, i * 5_000_000_000L + 1);
            if (sample != null) result.add(sample);
        }
        return result;
    }

    @Test public void bufferedAndQueuedObservationsAdvanceExactlyOnce() throws Exception {
        RecoveryFixture fixture = new RecoveryFixture();
        List<SampleClock.Sample> samples = samples();
        AtomicReference<String> failure = new AtomicReference<String>();
        try (InferenceWorker worker = new InferenceWorker(failure::set)) {
            int[] input = fixture.calibration.clone(); worker.initialize(input); Arrays.fill(input, 50);
            worker.sample(samples.get(0)); sound(worker, fixture.probes[0]);
            worker.sample(samples.get(1)); sound(worker, fixture.probes[1]);
            flush(worker);
            assertNull(failure.get());
            assertEquals(0, worker.states); assertEquals(0, worker.revision); assertEquals(0, worker.probes);
            assertEquals("collecting_probes", worker.recovery.stage); assertEquals(2, worker.recovery.completed);
            int sample = 2;
            for (int i = 2; i < fixture.probes.length; i++) {
                sound(worker, fixture.probes[i]);
                if (i >= 10 && i % 5 == 0 && sample < samples.size()) worker.sample(samples.get(sample++));
            }
            while (sample < samples.size()) worker.sample(samples.get(sample++));
            flush(worker);
            assertNull(failure.get());
            assertEquals(45, worker.probes); assertEquals(1, worker.states);
            assertTrue(worker.timersReady); assertTrue(worker.sharedReady);
            assertEquals(47, worker.revision);
            assertEquals("complete", worker.recovery.stage); assertEquals(439, worker.recovery.candidates);
            assertEquals(0, worker.pending());
        }
    }

    @Test public void uncertainOrPaidPrefixSelectsLegacyRecovery() throws Exception {
        for (boolean free : new boolean[] {true, false}) {
            RecoveryFixture fixture = new RecoveryFixture();
            AtomicReference<InferenceWorker> owner = new AtomicReference<InferenceWorker>();
            AtomicReference<String> failure = new AtomicReference<String>();
            CountDownLatch legacy = new CountDownLatch(1);
            InferenceWorker worker = new InferenceWorker(failure::set, (event, values) -> {
                if (event.equals("entity_recovery") && Arrays.asList(values).contains("legacy")) {
                    legacy.countDown(); owner.get().close();
                }
            });
            owner.set(worker);
            try {
                worker.initialize(fixture.calibration);
                sound(worker, fixture.probes[0]); sound(worker, fixture.probes[1]);
                worker.sound(fixture.probes[2].pitch, 22, free ? 23 : 22, free);
                assertTrue(legacy.await(5, TimeUnit.SECONDS));
                assertTrue(worker.executor.awaitTermination(10, TimeUnit.SECONDS));
                assertNull(failure.get()); assertEquals(0, worker.states); assertEquals(0, worker.revision);
            } finally { worker.close(); }
        }
    }

    @Test public void cancellationAfterOrdinaryBranchesPublishesNoModel() throws Exception {
        RecoveryFixture fixture = new RecoveryFixture();
        AtomicReference<InferenceWorker> owner = new AtomicReference<InferenceWorker>();
        AtomicReference<String> failure = new AtomicReference<String>();
        CountDownLatch cancelled = new CountDownLatch(1);
        InferenceWorker worker = new InferenceWorker(failure::set, (event, values) -> {
            if (!event.equals("entity_recovery")) return;
            Map<String, Object> fields = new HashMap<String, Object>();
            for (int i = 0; i < values.length; i += 2) fields.put((String)values[i], values[i + 1]);
            if (fields.get("stage").equals("ordinary") && fields.get("completed").equals(fields.get("total"))) {
                owner.get().close(); cancelled.countDown();
            }
        });
        owner.set(worker);
        try {
            worker.initialize(fixture.calibration);
            for (RecoveryProbe probe : fixture.prefix()) sound(worker, probe);
            assertTrue(cancelled.await(180, TimeUnit.SECONDS));
            assertTrue(worker.executor.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(0, worker.states); assertEquals(0, worker.probes); assertEquals(0, worker.revision);
            assertFalse(worker.timersReady); assertFalse(worker.sharedReady);
        } finally { worker.close(); }
    }
}
