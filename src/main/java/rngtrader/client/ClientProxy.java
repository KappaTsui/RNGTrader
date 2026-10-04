package rngtrader.client;

import cpw.mods.fml.common.FMLCommonHandler;
import rngtrader.RNGTrader;
import rngtrader.CommonProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.gui.GuiMerchant;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.network.play.server.*;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

public final class ClientProxy extends CommonProxy {
    private static final AtomicLong CONNECTIONS = new AtomicLong();
    private static final AttributeKey<Long> CONNECTION = new AttributeKey<Long>("rngtrader.connection");
    private TraderService service;

    @Override public void init(FMLInitializationEvent event) {
        service = new TraderService();
        ClientCommandHandler.instance.registerCommand(new TraderCommand(service));
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void connected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        final long connection = CONNECTIONS.incrementAndGet();
        event.manager.channel().attr(CONNECTION).set(connection);
        service.enqueueConnection(() -> service.connected(connection, String.valueOf(event.manager.channel().remoteAddress())));
        event.manager.channel().pipeline().addBefore("packet_handler", "rngtrader.observe", new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                final long time = System.nanoTime();
                if (message instanceof S38PacketPlayerListItem) {
                    S38PacketPlayerListItem p = (S38PacketPlayerListItem)message;
                    String name = p.func_149122_c(); boolean online = p.func_149121_d();
                    service.enqueueNetwork(connection, () -> service.playerInfo(name, online, time));
                } else if (message instanceof S29PacketSoundEffect) {
                    S29PacketSoundEffect p = (S29PacketSoundEffect)message;
                    TraderService.Sound sound = new TraderService.Sound(p.func_149212_c(), Math.round(p.func_149209_h() * 63),
                        p.func_149207_d(), p.func_149211_e(), p.func_149210_f(), time);
                    if (sound.name.startsWith("mob.villager.")) service.enqueueNetwork(connection, () -> service.sound(sound));
                } else if (message instanceof S3FPacketCustomPayload) {
                    S3FPacketCustomPayload p = (S3FPacketCustomPayload)message;
                    if ("MC|TrList".equals(p.func_149169_c())) {
                        byte[] data = p.func_149168_d().clone();
                        service.enqueueNetwork(connection, () -> service.onOffers(data, time));
                    }
                } else if (message instanceof S32PacketConfirmTransaction) {
                    S32PacketConfirmTransaction p = (S32PacketConfirmTransaction)message;
                    int window = p.func_148889_c(); short action = p.func_148890_d(); boolean ok = p.func_148888_e();
                    service.enqueueNetwork(connection, () -> service.confirm(window, action, ok, time));
                } else if (message instanceof S0DPacketCollectItem) {
                    int collector = ((S0DPacketCollectItem)message).func_149353_d();
                    service.enqueueNetwork(connection, () -> service.pickup(collector));
                } else if (message instanceof S03PacketTimeUpdate) {
                    long age = ((S03PacketTimeUpdate)message).func_149366_c();
                    service.enqueueNetwork(connection, () -> service.worldTime(age, time));
                } else if (message instanceof S2EPacketCloseWindow && service.replacingMerchant()) {
                    service.forcedClose = true;
                    service.enqueueNetwork(connection, service::serverClosed);
                }
                ctx.fireChannelRead(message);
            }
        });
    }
    @SubscribeEvent public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Long connection = event.manager.channel().attr(CONNECTION).get();
        service.enqueueConnection(() -> { if (connection != null && service.connectionEpoch() == connection) service.disconnect(); });
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            try { service.tick(); }
            catch (Exception error) { RNGTrader.LOG.error("Session failed", error); service.fail(error.toString()); }
        }
    }
    @SubscribeEvent public void screen(GuiOpenEvent event) {
        if (event.gui instanceof GuiMerchant && !(event.gui instanceof TraderScreen) && service.replacingMerchant()) {
            GuiMerchant original = (GuiMerchant)event.gui;
            event.gui = new TraderScreen(service, original.func_147035_g());
        } else if (event.gui == null && service.closingInterval() && service.internalClose()) {
            event.gui = new RefreshScreen(service);
        } else if (service.holding() && !service.forcedClose && !service.internalClose()
            && service.mc.thePlayer != null && service.mc.theWorld != null && !(event.gui instanceof GuiGameOver)
            && service.mc.currentScreen instanceof TraderScreen && event.gui != service.mc.currentScreen) {
            event.setCanceled(true);
        }
    }
}
