package rngtrader.client;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.IMerchant;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.client.event.GuiOpenEvent;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ScreenLifecycleTest {
    private static final class Fixture {
        final Minecraft mc = mock(Minecraft.class);
        final ClientProxy proxy = new ClientProxy();
        Fixture() throws Exception {
            mc.thePlayer = mock(EntityClientPlayerMP.class);
            mc.thePlayer.inventory = new InventoryPlayer(mc.thePlayer);
            mc.theWorld = mock(WorldClient.class);
            TraderService service = new TraderService();
            Field game = TraderService.class.getDeclaredField("mc"); game.setAccessible(true); game.set(service, mc);
            Field controller = ClientProxy.class.getDeclaredField("service"); controller.setAccessible(true); controller.set(proxy, service);
            service.phase = TraderService.Phase.STOPPED;
            mc.currentScreen = new TraderScreen(service, mock(IMerchant.class));
        }
        boolean canceled(GuiScreen next) {
            // FML's EventSubscriptionTransformer supplies this override for @Cancelable in a running client.
            GuiOpenEvent event = new GuiOpenEvent(next) { @Override public boolean isCancelable() { return true; } };
            proxy.screen(event); return event.isCanceled();
        }
    }
    @Test public void heldMerchantPreventsAccidentalScreenClosure() throws Exception {
        Fixture f = new Fixture(); assertTrue(f.canceled(null));
    }
    @Test public void unloadAllowsDisconnectScreenBeforeQueuedCleanup() throws Exception {
        Fixture f = new Fixture(); f.mc.thePlayer = null; f.mc.theWorld = null;
        assertFalse(f.canceled(new GuiScreen()));
    }
    @Test public void heldMerchantAllowsDeathScreen() throws Exception {
        Fixture f = new Fixture(); assertFalse(f.canceled(new GuiGameOver()));
    }
}
