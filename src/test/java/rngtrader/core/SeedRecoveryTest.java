package rngtrader.core;

import com.google.gson.*;
import com.seedfinding.latticg.util.LCG;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.LongStream;
import org.junit.Test;
import static org.junit.Assert.*;
import static rngtrader.core.BlacksmithRng.*;

public class SeedRecoveryTest {
    @Test public void freeProbeConstraintsRecoverHeldOutObservations() throws Exception {
        RecoveryFixture fixture = new RecoveryFixture();
        RngEngine engine = new RngEngine();
        List<RecoveryProgress> progress = new ArrayList<RecoveryProgress>();
        Thread owner = Thread.currentThread();
        engine.initialize(fixture.calibration, fixture.prefix(), p -> {
            assertSame(owner, Thread.currentThread()); progress.add(p);
        });
        assertEquals(439, engine.stateCount());
        assertEquals(1, engine.revision());
        assertFalse(engine.timersReady());
        assertTrue(progress.stream().anyMatch(p -> p.stage.equals("ordinary") && p.completed == 17 && p.candidates == 437));
        assertTrue(progress.stream().anyMatch(p -> p.stage.equals("rejections") && p.completed == 76));
        assertEquals("complete", progress.get(progress.size() - 1).stage);
        fixture.replay(engine);
        assertEquals(46, engine.revision());
        assertTrue(engine.timersReady());
        assertEquals(Collections.singleton("259037311595857:88:40:0:1:-80:0:0:true:true:null:-1:null"), RecoveryFixture.stateKeys(engine));
        replayTradingContinuation(engine);
    }

    private static int[] ints(JsonArray array) {
        int[] values = new int[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsInt();
        return values;
    }
    private void replayTradingContinuation(RngEngine engine) throws Exception {
        JsonObject fixture;
        try (Reader in = new InputStreamReader(getClass().getResourceAsStream("/recovery-continuation.json"), StandardCharsets.UTF_8)) {
            fixture = new JsonParser().parse(in).getAsJsonObject();
        }
        int[][] shared = new int[9][];
        for (int i = 0; i < 9; i++) shared[i] = ints(fixture.getAsJsonArray("shared").get(i).getAsJsonArray());
        engine.recoverShared(shared);
        List<OfferData> offers = new ArrayList<OfferData>(); offers.add(new OfferData(Kind.IRON_SHOVEL, 4, false));
        int refreshes = 0, samples = 0; int[] disabled = null; long anchor = 0;
        for (JsonElement element : fixture.getAsJsonArray("observations")) {
            JsonObject row = element.getAsJsonObject();
            switch (row.get("op").getAsString()) {
                case "sound":
                    int ticks = row.get("ticks").getAsInt();
                    engine.observe(row.get("pitch").getAsInt(), ticks, ticks, row.get("free").getAsBoolean());
                    break;
                case "shared": engine.observeShared(ints(row.getAsJsonArray("draws"))); samples++; break;
                case "close":
                    disabled = ints(row.getAsJsonArray("disabled")); anchor = row.get("anchor").getAsLong();
                    int wait = row.get("wait").getAsInt();
                    assertEquals("Refresh " + refreshes, wait, engine.plan(OfferData.mask(offers), disabled, wait, wait, 1));
                    break;
                case "refresh":
                    OfferData appended = new OfferData(Kind.valueOf(row.get("kind").getAsString()), row.get("price").getAsInt(), false);
                    engine.resumeAt(offers.size(), disabled, anchor,
                        new TickInterval(row.get("first").getAsLong(), row.get("last").getAsLong()), row.get("opened").getAsLong(), appended);
                    List<OfferData> next = new ArrayList<OfferData>(offers); next.add(appended); OfferData.verifyAppend(offers, next);
                    offers = next; refreshes++;
                    break;
                default: fail("Unknown observation");
            }
        }
        assertEquals(25, refreshes); assertEquals(1, engine.stateCount());
        assertTrue(OfferData.complete(offers)); assertTrue(samples > 400);
    }

    @Test public void allPitchBinsAndFourLiftsRetainTheirSeed() {
        Random source = new Random(149);
        Map<Integer, Long> examples = new TreeMap<Integer, Long>();
        for (int i = 0; examples.size() < 26 && i < 100000; i++) {
            long seed = source.nextLong() & MASK;
            examples.put(new Rng(seed).sound(), seed);
        }
        assertEquals(26, examples.size());
        long residue = 21732181123213L & MASK46;
        List<Long> seeds = new ArrayList<Long>(examples.values());
        for (int lift = 0; lift < 4; lift++) seeds.add(residue + lift * MOD46);
        for (long seed : seeds) {
            Rng rng = new Rng(seed); int[] pitches = new int[12];
            for (int i = 0; i < pitches.length; i++) pitches[i] = rng.sound();
            try (LongStream recovered = recover(pitches, offsets(pitches.length))) {
                assertTrue("Seed lost: " + seed, recovered.anyMatch(s -> s == seed));
            }
        }
    }

    @Test public void actualTimerPathsFitJointProfilesAndOffsetConstraints() {
        int[][] intervals = {{21, 39, 21}, {39, 21, 39}, {22, 22, 22}, {39, 39, 39}};
        for (int lift = 0; lift < 4; lift++) {
            long seed = (21732181123213L & MASK46) + lift * MOD46;
            int[] gaps = intervals[lift], pitches = new int[10], extra = new int[3];
            long[] offsets = offsets(10);
            Rng rng = new Rng(seed);
            for (int i = 0; i < 7; i++) pitches[i] = rng.sound();
            State state = new State(rng.seed, 1 + lift * 39, lift * 26, lift % 3);
            for (int i = 0; i < 3; i++) {
                long before = state.rng.calls;
                assertTrue(state.advance(gaps[i], 0, 0));
                extra[i] = (int)(state.rng.calls - before) - gaps[i];
                offsets[7 + i] = 14 + state.rng.calls;
                pitches[7 + i] = state.rng.sound(); state.ambient = -80;
            }
            assertTrue(SeedRecovery.timerProfiles(gaps).stream().anyMatch(p -> Arrays.equals(p, extra)));
            try (LongStream recovered = recover(pitches, offsets)) { assertTrue(recovered.anyMatch(s -> s == seed)); }
        }
    }

    @Test public void timerProfileAbstractionMatchesIndependentTickEnumeration() {
        for (int a : new int[] {21, 22, 39}) for (int b : new int[] {21, 22, 39}) for (int c : new int[] {21, 22, 39}) {
            int[] gaps = {a, b, c}, ends = {a, a + b, a + b + c};
            Set<Integer> looks = lookMasks(ends), villages = villageMasks(ends);
            assertEquals(looks, SeedRecovery.eventProfiles(ends, 81, 42, 81, 3));
            assertEquals(villages, SeedRecovery.eventProfiles(ends, 119, 70, 119, 1));
            Set<String> expected = new TreeSet<String>(), actual = new TreeSet<String>();
            for (int look : looks) for (int village : villages) {
                int[] counts = new int[3];
                for (int i = 0; i < 3; i++) counts[i] = ((look >> i) & 1) + ((village >> i) & 1);
                expected.add(Arrays.toString(counts));
            }
            for (int[] counts : SeedRecovery.timerProfiles(gaps)) actual.add(Arrays.toString(counts));
            assertEquals(expected, actual);
        }
        assertEquals(17, SeedRecovery.timerProfiles(new int[] {22, 22, 22}).size());
    }

    private static int bit(int[] ends, int tick) { return 1 << (tick <= ends[0] ? 0 : tick <= ends[1] ? 1 : 2); }
    private static int lookKey(int look, int phase, int mode, int mask) { return (((look * 3 + phase) * 2 + mode) << 3) | mask; }
    private static Set<Integer> lookMasks(int[] ends) {
        Set<Integer> states = new HashSet<Integer>();
        for (int look = 0; look <= 78; look++) for (int phase = 0; phase < 3; phase++) states.add(lookKey(look, phase, 1, 0));
        for (int tick = 1; tick <= ends[2]; tick++) {
            Set<Integer> next = new HashSet<Integer>();
            for (int key : states) {
                int mask = key & 7, mode = (key >> 3) & 1, packed = key >> 4;
                int look = packed / 3, phase = packed % 3;
                if (look <= 0) mode = 0;
                if (phase == 0 && mode != 1) {
                    for (int timer = 40; timer < 80; timer++) next.add(lookKey(timer - 1, 1, 1, mask | bit(ends, tick)));
                } else next.add(lookKey(look - mode, (phase + 1) % 3, mode, mask));
            }
            states = next;
        }
        Set<Integer> masks = new TreeSet<Integer>(); for (int state : states) masks.add(state & 7); return masks;
    }
    private static Set<Integer> villageMasks(int[] ends) {
        Set<Integer> states = new HashSet<Integer>();
        for (int timer = 1; timer < 120; timer++) states.add(timer << 3);
        for (int tick = 1; tick <= ends[2]; tick++) {
            Set<Integer> next = new HashSet<Integer>();
            for (int key : states) {
                int mask = key & 7, timer = (key >> 3) - 1;
                if (timer == 0) for (int reset = 70; reset < 120; reset++) next.add((reset << 3) | mask | bit(ends, tick));
                else next.add((timer << 3) | mask);
            }
            states = next;
        }
        Set<Integer> masks = new TreeSet<Integer>(); for (int state : states) masks.add(state & 7); return masks;
    }

    @Test public void rejectionLatticeRetainsBoundsAndPositionEndpoints() {
        assertEquals(84_934_656L, MASK - SeedRecovery.REJECTION_LOWER + 1);
        assertEquals(90, SeedRecovery.lastRejectionPosition(new int[] {22, 22, 22}));
        assertEquals(141, SeedRecovery.lastRejectionPosition(new int[] {39, 39, 39}));
        for (int bound : new int[] {40, 50, 1000}) {
            long lower = (((1L << 31) / bound) * bound) << 17;
            assertTrue(lower >= SeedRecovery.REJECTION_LOWER);
            for (long rejected : new long[] {lower, MASK}) for (int position : new int[] {15, 90, 141}) {
                long seed = LCG.JAVA.combine(-position).nextSeed(rejected);
                Rng rng = new Rng(seed); int[] pitches = new int[7];
                for (int i = 0; i < 7; i++) pitches[i] = rng.sound();
                try (LongStream recovered = new SeedRecovery.RejectionLattice(pitches).at(position)) {
                    assertFalse(recovered.isParallel());
                    assertTrue("Rejected state lost", recovered.anyMatch(s -> s == seed));
                }
            }
        }
    }

    @Test public void forcedAmbientLookVillageAndRepeatedRejectionsRemainCovered() {
        long[] seeds = {262801872227470L, 101527835846831L, 189213369906356L, 190441896365903L};
        for (int fixture = 0; fixture < seeds.length; fixture++) {
            long seed = seeds[fixture];
            Rng rng = new Rng(seed); int[] pitches = new int[7];
            for (int i = 0; i < 7; i++) pitches[i] = rng.sound();
            State state = new State(rng.seed, 1, 0, 0);
            assertTrue(state.advance(22, 0, 0));
            assertEquals(fixture == 3 ? 26 : 25, state.rng.calls);
            int position = fixture == 3 ? 15 : 15 + fixture;
            try (LongStream recovered = new SeedRecovery.RejectionLattice(pitches).at(position)) {
                assertTrue(recovered.anyMatch(s -> s == seed));
            }
            Tracker tracker = new Tracker(); tracker.coarse = new long[] {rng.seed};
            for (int probe = 0; probe < 3; probe++) {
                if (probe > 0) assertTrue(state.advance(22, 0, 0));
                int pitch = state.rng.sound(); state.ambient = -80;
                tracker.observe(pitch, 22, 22, true);
                final long expected = state.rng.seed;
                assertTrue(tracker.coarse != null ? Arrays.stream(tracker.coarse).anyMatch(s -> s == expected)
                    : tracker.states.stream().anyMatch(s -> s.rng.seed == expected));
            }
        }
    }

    @Test public void cancellationBeforeRejectionCompletionLeavesModelUnchanged() throws Exception {
        RecoveryFixture fixture = new RecoveryFixture();
        RngEngine engine = new RngEngine();
        try {
            engine.initialize(fixture.calibration, fixture.prefix(), p -> {
                if (p.stage.equals("ordinary") && p.completed == 0) Thread.currentThread().interrupt();
            });
            fail("Recovery should be cancelled");
        } catch (CancellationException expected) {
            assertEquals(0, engine.stateCount()); assertEquals(0, engine.revision());
        } finally { Thread.interrupted(); }
    }

    @Test public void recoveryProbeValidatesObservationDomain() {
        for (int[] invalid : new int[][] {{49, 22}, {76, 22}, {60, 20}, {60, 40}}) {
            try { new RecoveryProbe(invalid[0], invalid[1]); fail(); } catch (IllegalArgumentException expected) { }
        }
    }
}
