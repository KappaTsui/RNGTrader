package rngtrader.core;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static rngtrader.core.BlacksmithRng.*;
import com.seedfinding.latticg.util.LCG;

public class RngModelTest {
    @Test public void randomMatchesJavaIncludingRejections() {
        Random seeds = new Random(73);
        for (int t = 0; t < 1000; t++) {
            long seed = seeds.nextLong() & MASK;
            Random expected = new Random(seed ^ A); Rng actual = new Rng(seed);
            for (int i = 0; i < 50; i++) {
                assertEquals(expected.nextInt(1073741825), actual.nextInt(1073741825));
                assertEquals(expected.nextFloat(), actual.nextFloat(), 0);
                assertEquals(expected.nextDouble(), actual.nextDouble(), 0);
                assertEquals(expected.nextBoolean(), actual.nextBoolean());
            }
        }
    }
    @Test public void permutationDecodeAndCompleteRejectionRecovery() {
        long[] seeds = {1234567890123L, LCG.JAVA.combine(-1).nextSeed(MASK),
            LCG.JAVA.combine(-15).nextSeed(MASK), LCG.JAVA.combine(-85).nextSeed(MASK)};
        for (long seed : seeds) {
            List<Integer> order = new ArrayList<Integer>(); for (int i = 0; i < 12; i++) order.add(i);
            Collections.shuffle(order, new Random(seed ^ A));
            int[] p = order.stream().mapToInt(Integer::intValue).toArray();
            assertArrayEquals(ShuffleRng.samples(seed, 1)[0], ShuffleRng.decode(p));
            int[][] samples = ShuffleRng.samples(seed, 9);
            long after = ShuffleRng.replay(seed, ShuffleRng.flatten(samples, 9));
            assertArrayEquals(new long[] {after}, ShuffleRng.recover(samples));
        }
    }
    @Test public void pitchInversionRetainsTrueSeed() {
        long seed = 21732181123213L;
        Rng rng = new Rng(seed); int[] pitches = new int[12];
        for (int i = 0; i < pitches.length; i++) pitches[i] = rng.sound();
        assertTrue(Arrays.stream(recover(pitches, offsets(12)).toArray()).anyMatch(s -> s == seed));
    }
    @Test public void seedRecoveryStaysOnTheCallingWorker() {
        Rng r = new Rng(21732181123213L); int[] pitches = new int[12];
        for (int i = 0; i < pitches.length; i++) pitches[i] = r.sound();
        Thread owner = Thread.currentThread();
        java.util.stream.LongStream candidates = recover(pitches, offsets(12));
        assertFalse(candidates.isParallel());
        assertTrue(candidates.peek(s -> assertSame(owner, Thread.currentThread())).anyMatch(s -> s == 21732181123213L));
    }
    @Test public void everyAcceptedTimingBranchAppendsAndIronIsLast() {
        Random source = new Random(1003); int all = (1 << Kind.values().length) - 1;
        int[] masks = {1 << Kind.IRON_SHOVEL.ordinal(), all ^ (1 << Kind.IRON_INGOT.ordinal()) ^ (1 << Kind.CHAINMAIL_LEGGINGS.ordinal()),
            all ^ (1 << Kind.IRON_INGOT.ordinal())};
        for (int known : masks) {
            boolean found = false; int size = Integer.bitCount(known), disabled = size == 1 ? 1 : 0;
            for (int attempt = 0; attempt < 10000 && !found; attempt++) {
                Tracker tracker = new Tracker();
                State initial = new State(source.nextLong(), 1 + source.nextInt(119), source.nextInt(79), source.nextInt(3));
                initial.shuffleSeed = source.nextLong() & MASK; tracker.states.add(initial);
                String before = initial.key(); int wait = tracker.planNew(known, disabled, 2, 17, 1);
                assertEquals(before, initial.key()); if (wait < 0) continue;
                State actual = null;
                for (int delay = wait - 1; delay <= wait + 1; delay++) {
                    State s = initial.copy(); assertTrue(s.advance(delay, size, disabled)); s.customer = false; s.countdown = 40;
                    assertTrue(s.advance(42, size, disabled)); assertTrue(Tracker.newOffer(s, known));
                    if (delay == wait) actual = s;
                }
                tracker.resumeNew(size, disabled, wait - 1, wait + 1, 40, 44, actual.selected.kind, actual.selected.price);
                tracker.observeShuffle(ShuffleRng.samples(actual.shuffleSeed, 1)[0]);
                int retained = tracker.states.size();
                int[] bad = ShuffleRng.samples(123L, 1)[0];
                try { tracker.observeShuffle(bad); fail("Unexpected sample accepted"); }
                catch (IllegalStateException expected) { assertEquals(retained, tracker.states.size()); }
                found = true;
            }
            assertTrue("No valid fixture window", found);
        }
    }
    @Test public void disabledSingleOfferRestocksAtSizeTwo() {
        List<Offer> offers = new ArrayList<Offer>(); Offer first = new Offer(Kind.IRON_SHOVEL, 4); first.uses = 7; offers.add(first);
        restock(new Rng(17), offers); assertTrue(first.disabled());
        offers.add(new Offer(Kind.COAL, 16)); restock(new Rng(17), offers); assertFalse(first.disabled());
    }
}
