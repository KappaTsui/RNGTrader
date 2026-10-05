package rngtrader.core;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServerTickClockTest {
    private ServerTickClock ready() {
        ServerTickClock c = new ServerTickClock(); c.spawn(7,-24000); c.eligible(7,true);
        c.metadata(7,-24000,0); c.time(100);
        for (int i=1;i<=40;i++) { c.metadata(7,-24000+i,i*50_000_000L); if(i%20==0)c.time(100+i); }
        assertTrue(c.select()); assertEquals(140,c.tick()); return c;
    }
    @Test public void countsRepeatedEncodedValuesAndBatchedDelivery() {
        ServerTickClock c=ready();
        for(int i=0;i<20;i++) c.metadata(7,-23900,3_000_000_000L);
        c.time(160); assertEquals(160,c.tick()); assertTrue(c.fresh(3_010_000_000L));
        assertFalse(c.fresh(3_300_000_000L));
    }
    @Test public void initialSnapshotIsNotATick() {
        ServerTickClock c=new ServerTickClock();c.spawn(7,-24000);c.eligible(7,true);c.metadata(7,-24000,0);c.time(100);
        assertEquals(0,c.animals().iterator().next().count);assertEquals(ServerTickClock.UNKNOWN,c.tick());
    }
    @Test public void missingMarkerInvalidatesClock() {
        ServerTickClock c=ready();for(int i=1;i<20;i++)c.metadata(7,-23960+i,3_000_000_000L);
        c.time(160);assertEquals(ServerTickClock.UNKNOWN,c.tick());c.select();assertNull(c.active());
    }
    @Test public void rejectsBackwardAgeAndTrackerLoss() {
        ServerTickClock c=ready();c.metadata(7,-24000,3_000_000_000L);assertEquals(ServerTickClock.UNKNOWN,c.tick());
        c=ready();c.remove(7);assertEquals(ServerTickClock.UNKNOWN,c.tick());c.clear();assertTrue(c.animals().isEmpty());
    }
    @Test public void switchesOnlyToSynchronizedReplacement() {
        ServerTickClock c=ready();c.spawn(9,-24000);c.eligible(9,true);c.metadata(9,-24000,0);
        for(int i=1;i<=60;i++) {
            c.metadata(7,-400+i,i*50_000_000L);c.metadata(9,-24000+i,i*50_000_000L);
            if(i%20==0)c.time(140+i);
        }
        c.select(); assertEquals(9,c.active().entity);assertEquals(200,c.tick());
    }
    @Test public void bracketsCloseWithoutAssumingFutureArrival() {
        CloseReceipt receipt=new CloseReceipt();receipt.reply(150);assertFalse(receipt.complete());receipt.reply(152);
        assertTrue(new TickInterval(149,153).contains(receipt.interval()));
        assertFalse(new TickInterval(149,151).contains(receipt.interval()));
        try {receipt.reply(153);fail();} catch(IllegalStateException expected) { }
    }
}
