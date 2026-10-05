package rngtrader.core;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static rngtrader.core.BlacksmithRng.*;

public class ProcessingTickRegressionTest {
    private RngEngine failureState() throws Exception {
        // State recovered from the public observations immediately before the second failing refresh.
        State s=new State(201936938760726L,71,41,2);s.regeneration=52;s.shuffleSeed=22592484961986L;
        RngEngine e=new RngEngine();Field f=RngEngine.class.getDeclaredField("tracker");f.setAccessible(true);
        ((Tracker)f.get(e)).states.add(s);return e;
    }
    @Test public void recordedOfferCannotBeExplainedByWideningReopen() throws Exception {
        RngEngine e=failureState();int mask=(1<<Kind.IRON_SHOVEL.ordinal())|(1<<Kind.IRON_SWORD.ordinal());
        assertEquals(10,e.plan(mask,new int[]{1},10,10,1));
        OfferData actual=new OfferData(Kind.IRON_AXE,7,false);
        try {e.resume(2,new int[]{1},9,11,40,70,actual);fail();} catch(IllegalStateException expected) { }
        // A different close tick explains the public offer; a receipt must reject it against the original plan.
        assertFalse(new TickInterval(109,111).contains(new TickInterval(112,112)));
        e.resumeAt(2,new int[]{1},100,new TickInterval(112,112),156,actual);
        assertTrue(e.stateCount()>0);
    }
    @Test public void elapsedTicksBeyond44AreModeledWhenMeasured() throws Exception {
        RngEngine e=failureState();e.resumeAt(2,new int[]{1},100,new TickInterval(112,112),160,
            new OfferData(Kind.IRON_AXE,7,false));assertTrue(e.stateCount()>0);
    }
    @Test public void reopenBeforeCountdownCompletionIsRejected() throws Exception {
        try {failureState().resumeAt(2,new int[]{1},100,new TickInterval(112,114),153,
            new OfferData(Kind.IRON_AXE,7,false));fail();}catch(IllegalArgumentException expected) { }
    }
}
