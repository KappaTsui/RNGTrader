package rngtrader.client;

import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.chunk.IChunkProvider;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import rngtrader.core.*;

public class PreparationTest {
    @BeforeClass public static void bootstrap() throws Exception {
        Field instance = cpw.mods.fml.common.Loader.class.getDeclaredField("instance");
        instance.setAccessible(true); instance.set(null, mock(cpw.mods.fml.common.Loader.class));
        Bootstrap.func_151354_b();
    }
    private static final class Fixture {
        final Minecraft mc = mock(Minecraft.class);
        final EntityVillager villager = mock(EntityVillager.class);
        final IChunkProvider chunks = mock(IChunkProvider.class);
        final Roster roster = new Roster();
        Fixture() throws Exception {
            mc.thePlayer = mock(EntityClientPlayerMP.class); mc.thePlayer.capabilities = new PlayerCapabilities();
            mc.thePlayer.inventory = new InventoryPlayer(mc.thePlayer);
            mc.theWorld = mock(WorldClient.class);
            Field provider = World.class.getDeclaredField("provider"); provider.setAccessible(true);
            WorldProviderHell hell = new WorldProviderHell(); hell.dimensionId = -1; provider.set(mc.theWorld, hell);
            villager.posY = 64;
            Field box = net.minecraft.entity.Entity.class.getDeclaredField("boundingBox"); box.setAccessible(true);
            box.set(villager, AxisAlignedBB.getBoundingBox(.2, 64, .2, .8, 65.8, .8));
            when(villager.getProfession()).thenReturn(3);
            DataWatcher watcher = mock(DataWatcher.class);
            Field dataWatcher = net.minecraft.entity.Entity.class.getDeclaredField("dataWatcher");
            dataWatcher.setAccessible(true); dataWatcher.set(villager, watcher);
            when(villager.getDataWatcher()).thenReturn(watcher);
            when(watcher.getWatchableObjectFloat(6)).thenReturn(20f);
            IAttributeInstance health = mock(IAttributeInstance.class);
            when(health.getAttributeValue()).thenReturn(20d);
            when(villager.getEntityAttribute(SharedMonsterAttributes.maxHealth)).thenReturn(health);
            when(villager.getActivePotionEffects()).thenReturn(Collections.emptyList());
            when(mc.theWorld.getChunkProvider()).thenReturn(chunks);
            when(chunks.chunkExists(anyInt(), anyInt())).thenReturn(true);
            when(mc.theWorld.getBlock(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.air);
            when(mc.theWorld.getCollidingBoundingBoxes(any(), any())).thenReturn(Collections.singletonList(villager.boundingBox));
            when(mc.theWorld.getEntitiesWithinAABBExcludingEntity(any(), any())).thenReturn(Collections.emptyList());
            for (int i = 0; i < 12; i++) roster.observe("Player" + i, true, i);
            ItemStack[] inventory = mc.thePlayer.inventory.mainInventory;
            inventory[0] = new ItemStack(Items.emerald, 64); inventory[1] = new ItemStack(Items.emerald, 64);
            inventory[2] = new ItemStack(Items.emerald, 64); inventory[3] = new ItemStack(Items.emerald, 28);
            inventory[4] = new ItemStack(Items.coal, 23); inventory[5] = new ItemStack(Items.gold_ingot, 9); inventory[6] = new ItemStack(Items.diamond, 5);
        }
        Preparation.Report check() { return Preparation.check(mc, villager,
            Arrays.asList(new OfferData(BlacksmithRng.Kind.IRON_SHOVEL, 4, false)), roster, false, false, true); }
    }
    @Test public void clearLoadedAreaPassesWithServerOnlyUnknowns() throws Exception {
        Fixture f = new Fixture(); Preparation.Report r = f.check(); assertTrue(r.lines.toString(), r.ready);
        assertTrue(r.lines.stream().anyMatch(s -> s.startsWith("UNKNOWN Server village")));
    }
    @Test public void boundaryWaterAndMissingChunksBlockReadiness() throws Exception {
        Fixture f = new Fixture(); when(f.mc.theWorld.getBlock(16,72,16)).thenReturn(Blocks.flowing_water);
        Preparation.Report wet = f.check(); assertFalse(wet.ready);
        assertTrue(wet.lines.stream().anyMatch(s -> s.contains("Water (16, 72, 16)")));
        when(f.mc.theWorld.getBlock(16,72,16)).thenReturn(Blocks.air);
        when(f.chunks.chunkExists(-1,-1)).thenReturn(false);
        Preparation.Report unloaded = f.check(); assertFalse(unloaded.ready);
        assertTrue(unloaded.lines.stream().anyMatch(s -> s.contains("scan incomplete")));
    }
    @Test public void positiveBreedingAgeIsNotTreatedAsReadyAdult() throws Exception {
        Fixture f = new Fixture(); when(f.villager.getGrowingAge()).thenReturn(4000);
        Preparation.Report r = f.check(); assertFalse(r.ready);
        assertTrue(r.lines.stream().anyMatch(s -> s.contains("Age must be zero")));
    }
}
