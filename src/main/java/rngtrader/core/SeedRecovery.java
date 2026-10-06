package rngtrader.core;

import com.seedfinding.latticg.math.component.BigFraction;
import com.seedfinding.latticg.math.component.BigMatrix;
import com.seedfinding.latticg.math.component.BigVector;
import com.seedfinding.latticg.math.lattice.LLL.LLL;
import com.seedfinding.latticg.math.lattice.LLL.Params;
import com.seedfinding.latticg.math.lattice.enumeration.Enumerate;
import com.seedfinding.latticg.util.LCG;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import static rngtrader.core.BlacksmithRng.*;

/** Seven calibration pitches, three probe constraints, and complete rejection coverage. */
final class SeedRecovery {
    static final int CALIBRATION = 7, PROBES = 3, MAX_CANDIDATES = 5_000_000;
    static final long REJECTION_LOWER = (((1L << 31) / 1000) * 1000) << 17;
    private static final long K = (1 - A) / 4;

    static long[] recover(int[] calibration, RecoveryProbe[] probes, Consumer<RecoveryProgress> progress) {
        if (calibration.length != CALIBRATION || probes.length != PROBES)
            throw new IllegalArgumentException("Seven calibration pitches and three recovery probes required");
        for (int pitch : calibration)
            if (pitch < 50 || pitch > 75) throw new IllegalArgumentException("Adult pitch byte 50..75 required");
        int[] pitches = Arrays.copyOf(calibration, CALIBRATION + PROBES);
        int[] gaps = new int[PROBES];
        for (int i = 0; i < PROBES; i++) {
            pitches[CALIBRATION + i] = probes[i].pitch;
            gaps[i] = probes[i].ticks;
        }
        Meter meter = new Meter(progress);
        Set<Long> seeds = new LinkedHashSet<Long>();
        List<int[]> profiles = timerProfiles(gaps);
        meter.report("ordinary", 0, profiles.size(), 0);
        int done = 0;
        for (int[] extra : profiles) {
            Cancel.check();
            long[] offsets = offsets(pitches.length);
            for (int i = 0; i < PROBES; i++)
                offsets[CALIBRATION + i] = offsets[CALIBRATION + i - 1] + 2 + gaps[i] + extra[i];
            try (LongStream candidates = BlacksmithRng.recover(pitches, offsets)) {
                candidates.forEach(seed -> add(seeds, seed));
            }
            meter.report("ordinary", ++done, profiles.size(), seeds.size());
        }
        int last = lastRejectionPosition(gaps);
        RejectionLattice lattice = new RejectionLattice(calibration);
        meter.report("rejections", 0, last - 14, seeds.size());
        for (int position = 15; position <= last; position++) {
            Cancel.check();
            try (LongStream candidates = lattice.at(position)) {
                candidates.forEach(seed -> add(seeds, seed));
            }
            meter.report("rejections", position - 14, last - 14, seeds.size());
        }
        Cancel.check();
        meter.report("complete", 1, 1, seeds.size());
        return seeds.stream().mapToLong(Long::longValue).toArray();
    }

    private static void add(Set<Long> seeds, long seed) {
        Cancel.check();
        seeds.add(seed);
        if (seeds.size() > MAX_CANDIDATES)
            throw new IllegalStateException("Need a more informative batch: " + seeds.size() + " seeds");
    }

    static int lastRejectionPosition(int[] gaps) {
        // Four draws for the first two probe sounds, at most six optional AI calls.
        int last = 24;
        for (int ticks : gaps) last += ticks;
        return last;
    }

    /** Possible per-interval AI call counts, independent of the generated timer values. */
    static List<int[]> timerProfiles(int[] gaps) {
        if (gaps.length != PROBES) throw new IllegalArgumentException("Three probe intervals required");
        int[] ends = new int[PROBES];
        for (int i = 0; i < PROBES; i++) {
            if (gaps[i] < 21 || gaps[i] > 39) throw new IllegalArgumentException("Probe interval must be 21..39 ticks");
            ends[i] = gaps[i] + (i == 0 ? 0 : ends[i - 1]);
        }
        Set<Integer> looks = eventProfiles(ends, 81, 42, 81, 3);
        Set<Integer> villages = eventProfiles(ends, 119, 70, 119, 1);
        Map<Integer, int[]> result = new TreeMap<Integer, int[]>();
        for (int look : looks) for (int village : villages) {
            int[] extra = new int[PROBES]; int key = 0;
            for (int i = 0; i < PROBES; i++) {
                extra[i] = ((look >>> i) & 1) + ((village >>> i) & 1);
                key = 3 * key + extra[i];
            }
            result.put(key, extra);
        }
        return new ArrayList<int[]>(result.values());
    }

    private static int intervalBit(int[] ends, int tick) {
        int i = 0;
        while (tick > ends[i]) i++;
        return 1 << i;
    }

    static Set<Integer> eventProfiles(int[] ends, int firstMax, int periodMin, int periodMax, int step) {
        int horizon = ends[ends.length - 1];
        boolean[][] reached = new boolean[horizon + 1][1 << ends.length];
        Set<Integer> profiles = new TreeSet<Integer>();
        if (firstMax > horizon) profiles.add(0);
        for (int tick = 1; tick <= Math.min(firstMax, horizon); tick++)
            reached[tick][intervalBit(ends, tick)] = true;
        for (int tick = 1; tick <= horizon; tick++) {
            Cancel.check();
            for (int mask = 0; mask < reached[tick].length; mask++) if (reached[tick][mask]) {
                for (int period = periodMin; period <= periodMax; period += step) {
                    int next = tick + period;
                    if (next > horizon) profiles.add(mask);
                    else reached[next][mask | intervalBit(ends, next)] = true;
                }
            }
        }
        return profiles;
    }

    /** Box intersection for coordinates (rejected 48-bit state, seven signed 46-bit differences). */
    static final class RejectionLattice {
        final int[] pitches;
        final BigVector lower, upper;
        final BigMatrix scale, unscale;

        RejectionLattice(int[] pitches) {
            if (pitches.length != CALIBRATION) throw new IllegalArgumentException("Seven calibration pitches required");
            this.pitches = pitches.clone();
            int dimension = CALIBRATION + 1;
            long[] lo = new long[dimension], hi = new long[dimension];
            lo[0] = REJECTION_LOWER; hi[0] = MASK;
            BigInteger[] widths = new BigInteger[dimension];
            BigInteger lcm = BigInteger.ONE;
            for (int i = 1; i < dimension; i++) {
                lo[i] = differenceLower(pitches[i - 1]);
                hi[i] = differenceUpper(pitches[i - 1]);
            }
            for (int i = 0; i < dimension; i++) {
                widths[i] = BigInteger.valueOf(hi[i] - lo[i] + 1);
                lcm = lcm.divide(lcm.gcd(widths[i])).multiply(widths[i]);
            }
            lower = new BigVector(lo); upper = new BigVector(hi);
            scale = new BigMatrix(dimension, dimension); unscale = new BigMatrix(dimension, dimension);
            for (int i = 0; i < dimension; i++) {
                BigInteger weight = lcm.divide(widths[i]);
                scale.set(i, i, new BigFraction(weight));
                unscale.set(i, i, new BigFraction(BigInteger.ONE, weight));
            }
        }

        LongStream at(int position) {
            if (position < 15) throw new IllegalArgumentException("Rejection must follow calibration");
            Cancel.check();
            int dimension = CALIBRATION + 1;
            BigMatrix basis = new BigMatrix(dimension, dimension);
            BigVector origin = new BigVector(dimension);
            basis.set(0, 0, BigFraction.ONE);
            for (int i = 0; i < CALIBRATION; i++) {
                LCG jump = LCG.JAVA.combine(2L * i + 1 - position);
                basis.set(0, i + 1, new BigFraction((K * jump.multiplier) & MASK46));
                basis.set(i + 1, i + 1, new BigFraction(MOD46));
                origin.set(i + 1, new BigFraction((K * jump.addend - 3) & MASK46));
            }
            BigMatrix reduced = LLL.reduce(basis.multiply(scale), new Params().setDelta(Params.recommendedDelta))
                .getReducedBasis().multiply(unscale);
            Cancel.check();
            LCG rewind = LCG.JAVA.combine(-position);
            return Enumerate.enumerate(reduced.transpose(), lower, upper, origin).sequential()
                .peek(point -> Cancel.check())
                .mapToLong(point -> rewind.nextSeed(point.get(0).getNumerator().longValueExact()))
                .filter(seed -> {
                    Rng rng = new Rng(seed);
                    for (int pitch : pitches) if (rng.sound() != pitch) return false;
                    return true;
                });
        }
    }

    private static final class Meter {
        final Consumer<RecoveryProgress> listener;
        final ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
        final long start = System.nanoTime(), used = cpuTime();
        Meter(Consumer<RecoveryProgress> listener) { this.listener = listener; }
        long cpuTime() {
            return cpu.isCurrentThreadCpuTimeSupported() && cpu.isThreadCpuTimeEnabled()
                ? cpu.getCurrentThreadCpuTime() : -1;
        }
        void report(String stage, int completed, int total, int candidates) {
            if (listener != null) listener.accept(new RecoveryProgress(stage, completed, total, candidates,
                System.nanoTime() - start, used < 0 ? -1 : cpuTime() - used));
        }
    }
}
