package rngtrader.core;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static rngtrader.core.BlacksmithRng.Kind.*;

public class BudgetRosterTest {
    @Test public void fullShovelBudgetAndMissingItems() {
        ResourceBudget budget = new ResourceBudget(Arrays.asList(new OfferData(IRON_SHOVEL, 4, false)), false, false);
        assertEquals(220, budget.required(388)); assertEquals(23, budget.required(263));
        assertEquals(9, budget.required(266)); assertEquals(5, budget.required(264)); assertEquals(0, budget.required(265));
        assertEquals(7, budget.outputSlots);
        Map<Integer,Integer> have = new HashMap<Integer,Integer>(budget.payments);
        assertTrue(budget.missing(have).isEmpty());
        have.put(388, 219); assertEquals(Collections.singletonMap(388, 1), budget.missing(have));
    }
    @Test public void existingOffersAreNotChargedAgain() {
        ResourceBudget b = new ResourceBudget(Arrays.asList(new OfferData(COAL, 16, false),
            new OfferData(GOLD_INGOT, 8, false), new OfferData(IRON_SHOVEL, 4, false)), false, false);
        assertEquals(0, b.required(263)); assertEquals(0, b.required(266)); assertEquals(220, b.required(388));
        ResourceBudget armed = new ResourceBudget(Arrays.asList(new OfferData(IRON_SHOVEL, 4, false)), true, true);
        assertEquals(192, armed.required(388));
    }
    @Test public void diamondCalibrationAndCompletedList() {
        ResourceBudget b = new ResourceBudget(Arrays.asList(new OfferData(DIAMOND, 5, false)), false, false);
        assertEquals(35, b.required(264)); assertEquals(197, b.required(388)); assertEquals(1, b.outputSlots);
        List<OfferData> complete = new ArrayList<OfferData>();
        for (BlacksmithRng.Kind k : BlacksmithRng.Kind.values()) if (k != IRON_INGOT) complete.add(new OfferData(k, k.min, false));
        complete.add(new OfferData(IRON_INGOT, 8, false));
        assertTrue(OfferData.complete(complete));
        assertTrue(new ResourceBudget(complete, false, false).payments.isEmpty());
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsPrematureIron() {
        new ResourceBudget(Arrays.asList(new OfferData(IRON_INGOT, 8, false)), false, false);
    }
    @Test public void initialSnapshotDuplicatePingAndRejoin() {
        Roster roster = new Roster();
        for (int i = 0; i < 12; i++) roster.observe("P" + i, true, 1_000_000L + i);
        assertTrue(roster.ready()); long revision = roster.revision();
        roster.observe("P3", true, 2_000_000L); assertEquals(revision, roster.revision());
        roster.observe("P3", false, 3_000_000L); roster.observe("P3", true, 4_000_000L);
        assertEquals("P3", roster.names().get(11));
    }
    @Test public void heartbeatRecoversRemoteRespawnOrder() {
        Roster roster = new Roster();
        for (int i = 0; i < 12; i++) roster.observe("P" + i, true, i);
        List<String> changed = new ArrayList<String>(roster.names()); changed.remove("P4"); changed.add("P4");
        for (int i = 0; i < 12; i++) roster.observe(changed.get(i), true, 30_000_000_000L + i * 50_000_000L);
        assertEquals(changed, roster.names());
        assertEquals("periodic server player-list order", roster.source());
    }
    @Test(expected=IllegalArgumentException.class) public void incompleteStatusIsNotAPermutation() {
        Roster.decode(Arrays.asList("A"), Arrays.asList("A"));
    }
    @Test public void cachedRepliesAndMissingSamples() {
        SampleClock clock = new SampleClock(); int[] a = new int[11], b = new int[11]; b[0] = 1;
        assertNull(clock.accept(a, 1_000_000_000L, 1_001_000_000L));
        SampleClock.Sample first = clock.accept(b, 5_000_000_000L, 5_001_000_000L);
        assertFalse(first.contiguous); assertEquals(1_000_000_000L, first.earliest);
        assertNull(clock.accept(b, 5_250_000_000L, 5_251_000_000L));
        assertTrue(clock.accept(a, 10_000_000_000L, 10_001_000_000L).contiguous);
        try { clock.accept(a, 16_000_000_000L, 16_001_000_000L); fail("missing sample accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
