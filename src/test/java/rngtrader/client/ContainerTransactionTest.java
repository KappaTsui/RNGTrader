package rngtrader.client;

import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.IMerchant;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerMerchant;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import rngtrader.core.*;

public class ContainerTransactionTest {
    @BeforeClass public static void bootstrap() throws Exception {
        // Registry ownership is irrelevant to container behavior. Avoid starting FML's game class loader in JUnit.
        Field instance = cpw.mods.fml.common.Loader.class.getDeclaredField("instance");
        instance.setAccessible(true); instance.set(null, mock(cpw.mods.fml.common.Loader.class));
        Bootstrap.func_151354_b();
    }

    private static final class Fixture implements TradeExecutor.Sink {
        final Minecraft mc = mock(Minecraft.class);
        final ContainerMerchant container;
        final TradeExecutor task;
        final List<Integer> dropped = new ArrayList<Integer>();
        long now = System.nanoTime(), lastSound = now, pickups;
        String failure;
        int completed, soundsToSend = 7;
        Fixture() throws Exception {
            mc.theWorld = mock(WorldClient.class); mc.theWorld.isRemote = true;
            mc.thePlayer = mock(EntityClientPlayerMP.class);
            mc.thePlayer.inventory = new InventoryPlayer(mc.thePlayer);
            NetHandlerPlayClient net = mock(NetHandlerPlayClient.class);
            Field field = EntityClientPlayerMP.class.getDeclaredField("sendQueue"); field.setAccessible(true); field.set(mc.thePlayer, net);
            MerchantRecipeList recipes = new MerchantRecipeList();
            recipes.add(new MerchantRecipe(new ItemStack(Items.emerald, 4), new ItemStack(Items.iron_shovel)));
            IMerchant merchant = mock(IMerchant.class);
            when(merchant.getRecipes(any(EntityPlayer.class))).thenReturn(recipes);
            when(merchant.getCustomer()).thenReturn(mc.thePlayer);
            container = new ContainerMerchant(mc.thePlayer.inventory, merchant, mc.theWorld); container.windowId = 1;
            mc.thePlayer.openContainer = container;
            // Deliberately split payments and place an identical original tool in a shift-click destination area.
            mc.thePlayer.inventory.mainInventory[0] = new ItemStack(Items.emerald, 13);
            mc.thePlayer.inventory.mainInventory[1] = new ItemStack(Items.emerald, 51);
            ItemStack original = new ItemStack(Items.iron_shovel); original.setStackDisplayName("Original tool");
            mc.thePlayer.inventory.mainInventory[8] = original;
            task = new TradeExecutor(mc, this, new OfferData(BlacksmithRng.Kind.IRON_SHOVEL, 4, false), 0, true);
            doAnswer(invocation -> {
                Packet packet = (Packet)invocation.getArguments()[0];
                C0EPacketClickWindow click = (C0EPacketClickWindow)packet;
                if (click.func_149544_d() == 2 && click.func_149542_h() == 1) {
                    for (int i = 0; i < soundsToSend; i++) task.sound(new TraderService.Sound("mob.villager.yes", 63, 0, 0, 0, now));
                }
                task.confirm(1, click.func_149547_f(), true, now);
                return null;
            }).when(net).addToSendQueue(any(Packet.class));
        }
        public void select(int index) { container.setCurrentRecipeIndex(index); lastSound = now; }
        public void complete(List<TraderService.Sound> sounds, boolean batch) { completed++; assertEquals(7, sounds.size()); assertTrue(batch); }
        public void fail(String message) { failure = message; }
        public void log(String event, Object... values) {
            if (event.equals("drop_purchased_output")) dropped.add((Integer)values[1]);
        }
        public long lastSoundTime() { return lastSound; }
        public long pickupRevision() { return pickups; }
        void run() { for (int i = 0; i < 600 && !task.finished(); i++) { now += 50_000_000L; task.tick(now); } }
    }

    @Test public void paysAcrossStacksAndDropsOnlyNewGear() throws Exception {
        Fixture f = new Fixture(); f.run(); assertNull(f.failure); assertTrue(f.task.finished()); assertEquals(1, f.completed);
        assertEquals(7, f.dropped.size());
        assertEquals("Original tool", f.mc.thePlayer.inventory.mainInventory[8].getDisplayName());
        int emeralds = 0, shovels = 0;
        for (ItemStack stack : f.mc.thePlayer.inventory.mainInventory) if (stack != null) {
            if (stack.getItem() == Items.emerald) emeralds += stack.stackSize;
            if (stack.getItem() == Items.iron_shovel) shovels += stack.stackSize;
        }
        assertEquals(36, emeralds); assertEquals(1, shovels); assertNull(f.mc.thePlayer.inventory.getItemStack());
    }
    @Test public void insufficientStockNeverDropsPredictedOutputs() throws Exception {
        Fixture f = new Fixture(); f.soundsToSend = 3; f.run();
        assertNotNull(f.failure); assertTrue(f.dropped.isEmpty()); assertEquals(0, f.completed);
        assertEquals("Original tool", f.mc.thePlayer.inventory.mainInventory[8].getDisplayName());
        assertEquals(16, f.container.getSlot(0).getStack().stackSize);
        int shovels = 0;
        for (ItemStack stack : f.mc.thePlayer.inventory.mainInventory) if (stack != null && stack.getItem() == Items.iron_shovel) shovels++;
        assertEquals(4, shovels);
    }
    @Test public void stopSettlesPendingClickWithoutAnotherAction() throws Exception {
        Fixture f = new Fixture();
        f.task.tick(f.now); f.now += 50_000_000L; f.task.tick(f.now);
        assertTrue(f.task.hasPendingClick()); f.task.stop();
        f.task.settleStopped(f.now + 200_000_000L);
        assertFalse(f.task.hasPendingClick()); assertTrue(f.task.finished()); assertTrue(f.dropped.isEmpty());
    }
}
