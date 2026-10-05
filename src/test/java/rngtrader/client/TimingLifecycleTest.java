package rngtrader.client;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ContainerMerchant;
import net.minecraft.inventory.Slot;
import net.minecraft.world.World;
import org.junit.Test;
import rngtrader.core.CloseReceipt;
import rngtrader.core.SampleClock;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TimingLifecycleTest {
    private static void field(Object object, String name, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }
    private static final class CommitFixture implements AutoCloseable {
        final TraderService service = new TraderService();
        final Minecraft mc = mock(Minecraft.class);
        final InferenceWorker worker = new InferenceWorker(why -> fail(why));
        CommitFixture() throws Exception {
            mc.thePlayer = mock(EntityClientPlayerMP.class);
            mc.thePlayer.inventory = new InventoryPlayer(mc.thePlayer);
            Field connection = EntityClientPlayerMP.class.getDeclaredField("sendQueue");
            connection.setAccessible(true); connection.set(mc.thePlayer, mock(NetHandlerPlayClient.class));
            mc.theWorld = mock(WorldClient.class);
            Field entities = World.class.getDeclaredField("loadedEntityList");
            entities.setAccessible(true); entities.set(mc.theWorld, Collections.emptyList());
            EntityVillager target = mock(EntityVillager.class); target.worldObj = mc.theWorld;
            EntityCow cow = mock(EntityCow.class); cow.posX = 20;
            when(mc.theWorld.getEntityByID(7)).thenReturn(cow);
            ContainerMerchant merchant = mock(ContainerMerchant.class);
            when(merchant.getSlot(anyInt())).thenReturn(mock(Slot.class));
            mc.thePlayer.openContainer = merchant;
            field(service, "mc", mc); field(service, "target", target); field(service, "inference", worker);
            field(service, "calibrated", true); field(service, "armed", true);
            field(service, "environment", new Preparation.Report());
            field(service, "lastEnvironmentCheck", System.nanoTime());
            service.clock.spawn(7, -24000); service.clock.eligible(7, true);
            service.clock.metadata(7, -24000, 0); service.clock.time(100);
            for (int i=1; i<=40; i++) {
                service.clock.metadata(7, -24000+i, System.nanoTime());
                if (i%20 == 0) service.clock.time(100+i);
            }
            service.clock.select(); field(service, "lastSoundTick", 140L);
            Class<?> plan = Class.forName("rngtrader.client.TraderService$Plan");
            Constructor<?> constructor = plan.getDeclaredConstructor(int.class, long.class, long.class,
                long.class, long.class, long.class); constructor.setAccessible(true);
            field(service, "scheduled", constructor.newInstance(5, 136L, 0L, service.roster.revision(),
                worker.revision, service.clock.revision()));
            SampleClock samples = new SampleClock();
            samples.accept(new int[] {0}, 1, 2);
            field(service, "lastSample", samples.accept(new int[] {1}, 3, 4));
            service.phase = TraderService.Phase.WAITING;
        }
        void tick() throws Exception {
            field(service, "lastEnvironmentCheck", System.nanoTime());
            field(service, "lastMetrics", System.nanoTime());
            service.clock.active().received = System.nanoTime();
            service.tick();
        }
        void rejected() throws Exception {
            tick();
            assertEquals(TraderService.Phase.WAITING, service.phase);
            verify(mc.thePlayer, never()).closeScreen();
            Field scheduled = TraderService.class.getDeclaredField("scheduled"); scheduled.setAccessible(true);
            assertNull(scheduled.get(service));
        }
        @Override public void close() { worker.close(); }
    }
    @Test public void freshForecastCanCommit() throws Exception {
        try (CommitFixture f = new CommitFixture()) {
            f.tick(); assertEquals(TraderService.Phase.CLOSING, f.service.phase);
            verify(f.mc.thePlayer).closeScreen();
        }
    }
    @Test public void changedObservationRejectsForecast() throws Exception {
        try (CommitFixture f = new CommitFixture()) { field(f.service, "observationVersion", 1L); f.rejected(); }
    }
    @Test public void changedModelRejectsForecastAtCommit() throws Exception {
        try (CommitFixture f = new CommitFixture()) { f.worker.revision++; f.rejected(); }
    }
    @Test public void busyWorkerRejectsForecastAtCommit() throws Exception {
        try (CommitFixture f = new CommitFixture()) { f.worker.busy = true; f.rejected(); }
    }
    @Test public void missedMarkerRejectsForecast() throws Exception {
        try (CommitFixture f = new CommitFixture()) {
            f.service.clock.metadata(7, -23959, System.nanoTime()); f.rejected();
        }
    }
    @Test public void rosterChangeRejectsForecast() throws Exception {
        try (CommitFixture f = new CommitFixture()) { f.service.roster.observe("NewPlayer", true, 1); f.rejected(); }
    }
    @Test public void receiptAfterClockLossStopsTheSession() throws Exception {
        TraderService service = new TraderService();
        field(service, "mc", mock(Minecraft.class));
        field(service, "receipt", new CloseReceipt());
        service.phase = TraderService.Phase.CLOSING;
        service.completionReply(0);
        assertEquals(TraderService.Phase.ERROR, service.phase);
        assertTrue(service.messages().get(0).contains("tick anchor"));
    }
    @Test public void queuedObservationSuppressesForecast() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<InferenceWorker.Forecast> result = new AtomicReference<InferenceWorker.Forecast>();
        InferenceWorker worker = new InferenceWorker(why -> fail(why));
        worker.sharedReady = true; worker.probes = 45;
        try {
            worker.executor.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            // Empty stock is a tripwire: executing this stale forecast would throw in RngEngine.plan.
            worker.plan(1, new int[0], 3, 17, 1, forecast -> { result.set(forecast); done.countDown(); });
            worker.invalidSample();
            assertEquals(2, worker.pending());
            release.countDown();
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(-1, result.get().delay);
        } finally { release.countDown(); worker.close(); }
    }
}
