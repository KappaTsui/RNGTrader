package rngtrader;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = "rngtrader", name = "RNGTrader", version = "1.1.0", acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public final class RNGTrader {
    public static final Logger LOG = LogManager.getLogger("RNGTrader");
    @SidedProxy(clientSide = "rngtrader.client.ClientProxy", serverSide = "rngtrader.CommonProxy")
    public static CommonProxy proxy;
    @Mod.EventHandler public void init(FMLInitializationEvent event) { proxy.init(event); }
}
