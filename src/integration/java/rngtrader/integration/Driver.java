package rngtrader.integration;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.util.MovingObjectPosition;
import rngtrader.RNGTrader;
import java.lang.reflect.*;

/** Integration-only operator; the production controller performs every trade. */
@Mod(modid="rngtrader_test_driver", name="RNGTrader integration driver", version="1", acceptableRemoteVersions="*")
public final class Driver {
    private boolean connecting, started;
    private long readyAt;
    private long menuAt;
    @Mod.EventHandler public void init(FMLInitializationEvent event) { FMLCommonHandler.instance().bus().register(this); }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!connecting && mc.currentScreen instanceof GuiMainMenu) {
            if (menuAt == 0) menuAt = System.nanoTime();
            if (System.nanoTime() - menuAt < 5_000_000_000L) return;
            connecting = true;
            mc.gameSettings.limitFramerate = 30;
            mc.gameSettings.renderDistanceChunks = 2;
            mc.gameSettings.pauseOnLostFocus = false;
            mc.gameSettings.particleSetting = 2;
            cpw.mods.fml.client.FMLClientHandler.instance().setupServerList();
            cpw.mods.fml.client.FMLClientHandler.instance().connectToServer(mc.currentScreen,
                new ServerData("Integration", "127.0.0.1:" + Integer.getInteger("rngtrader.testPort", 25578)));
        }
        if (started || mc.thePlayer == null || mc.theWorld == null) return;
        Field serviceField = RNGTrader.proxy.getClass().getDeclaredField("service"); serviceField.setAccessible(true);
        Object service = serviceField.get(RNGTrader.proxy);
        Field rosterField = service.getClass().getDeclaredField("roster"); rosterField.setAccessible(true);
        Object roster = rosterField.get(service);
        if (!(Boolean)roster.getClass().getMethod("ready").invoke(roster)) { readyAt = 0; return; }
        if (readyAt == 0) readyAt = System.nanoTime();
        if (System.nanoTime() - readyAt < 4_000_000_000L) return;
        for (Object value : mc.theWorld.loadedEntityList) {
            if (!(value instanceof EntityVillager)) continue;
            Entity entity = (Entity)value;
            if (mc.thePlayer.getDistanceSqToEntity(entity) > 9) continue;
            mc.thePlayer.rotationYaw = -90; mc.thePlayer.rotationPitch = 0;
            mc.objectMouseOver = new MovingObjectPosition(entity);
            mc.displayGuiScreen(null);
            Method command = service.getClass().getDeclaredMethod("command", String.class); command.setAccessible(true);
            command.invoke(service, "start");
            started = true;
            RNGTrader.LOG.info("Integration driver requested start for entity {}", entity.getEntityId());
            break;
        }
    }
}
