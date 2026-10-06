package rngtrader.core;

import com.google.gson.*;
import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Run through Gradle's benchmarkSeedRecovery task; timings are outside unit-test assertions. */
public final class SeedRecoveryBenchmark {
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final class Measurement {
        final String method;
        final List<RecoveryProgress> progress = new ArrayList<RecoveryProgress>();
        long initializeNanos, initializeCpuNanos, replayNanos, replayCpuNanos;
        int candidates, finalStates;
        Set<String> stateKeys;
        Measurement(String method) { this.method = method; }
    }
    private static Measurement run(RecoveryFixture fixture, boolean lookahead) throws Exception {
        Measurement result = new Measurement(lookahead ? "lookahead" : "legacy");
        RngEngine engine = new RngEngine();
        System.out.println("Starting " + result.method);
        ComputeBudget.enable();
        try {
            long start = System.nanoTime(), cpu = CPU.getCurrentThreadCpuTime();
            if (lookahead) engine.initialize(fixture.calibration, fixture.prefix(), progress -> {
                result.progress.add(progress);
                if (progress.completed == 0 || progress.completed == progress.total)
                    System.out.println(progress.stage + " " + progress.completed + "/" + progress.total
                        + " candidates=" + progress.candidates + " CPU=" + progress.cpuNanos / 1e9);
            });
            else engine.initialize(fixture.calibration);
            result.initializeNanos = System.nanoTime() - start;
            result.initializeCpuNanos = CPU.getCurrentThreadCpuTime() - cpu;
            result.candidates = engine.stateCount();
            System.out.println(result.method + " initialized: " + result.candidates + " candidates, CPU=" + result.initializeCpuNanos / 1e9);
            start = System.nanoTime(); cpu = CPU.getCurrentThreadCpuTime();
            fixture.replay(engine);
            result.replayNanos = System.nanoTime() - start;
            result.replayCpuNanos = CPU.getCurrentThreadCpuTime() - cpu;
            result.finalStates = engine.stateCount(); result.stateKeys = RecoveryFixture.stateKeys(engine);
            if (!engine.timersReady() || result.finalStates != 1) throw new AssertionError("Entity state did not converge");
            System.out.println(result.method + " state=" + result.stateKeys);
            return result;
        } finally { ComputeBudget.clear(); }
    }

    public static void main(String[] args) throws Exception {
        if (!CPU.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("Thread CPU time required");
        if (!CPU.isThreadCpuTimeEnabled()) CPU.setThreadCpuTimeEnabled(true);
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);
        // Exercise LattiCG and the Java RNG implementation before either measurement.
        BlacksmithRng.Rng rng = new BlacksmithRng.Rng(21732181123213L);
        int[] warmup = new int[12]; for (int i = 0; i < warmup.length; i++) warmup[i] = rng.sound();
        for (int i = 0; i < 2; i++) BlacksmithRng.recover(warmup, BlacksmithRng.offsets(warmup.length)).count();
        RecoveryFixture fixture = new RecoveryFixture();
        Measurement optimized = run(fixture, true), baseline = run(fixture, false);
        if (!optimized.stateKeys.equals(baseline.stateKeys)) throw new AssertionError("Recovered states differ");
        double speedup = (double)baseline.initializeCpuNanos / optimized.initializeCpuNanos;
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("javaVm", System.getProperty("java.vm.name"));
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("computeBudget", "5 ms computation slices, up to 5 ms rest");
        report.put("measurements", Arrays.asList(optimized, baseline));
        report.put("initializeCpuSpeedup", speedup);
        report.put("matchingFinalState", true); report.put("cpuTargetMet", speedup >= 3.0);
        Path output = Paths.get(args[0]); Files.createDirectories(output.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(report, writer); writer.write('\n');
        }
        System.out.println("Initialization CPU speedup: " + speedup + "; report: " + output);
        if (speedup < 3.0) throw new AssertionError("Initialization CPU target was not met");
    }
}
