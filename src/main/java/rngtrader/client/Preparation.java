package rngtrader.client;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import rngtrader.core.*;

final class Preparation {
    static final class Report {
        final List<String> lines = new ArrayList<String>();
        boolean ready = true;
        ResourceBudget budget;
        void pass(String s) { lines.add("PASS " + s); }
        void fail(String s) { ready = false; lines.add("FAIL " + s); }
        void unknown(String s) { lines.add("UNKNOWN " + s); }
    }
    static Report check(Minecraft mc, EntityVillager villager, List<OfferData> offers,
                        Roster roster, boolean calibrated, boolean armed, boolean scanBlocks) {
        Report report = new Report();
        if (mc.thePlayer == null || mc.theWorld == null || villager == null || villager.isDead) {
            report.fail("No loaded target Blacksmith"); return report;
        }
        if (mc.thePlayer.capabilities.isCreativeMode) report.fail("Survival player required");
        if (villager.getProfession() != 3) report.fail("Target is not a Blacksmith");
        else report.pass("Blacksmith profession");
        if (villager.getGrowingAge() != 0) report.fail("Age must be zero; observed " + villager.getGrowingAge());
        else report.pass("Synchronized Age = 0");
        if (mc.thePlayer.getDistanceSqToEntity(villager) > 9.0) report.fail("Stand within 3 blocks of the target");
        else report.pass("Customer within 3 blocks");
        if (villager.isInWater() || villager.handleLavaMovement() || villager.isBurning()) report.fail("Target is touching liquid or fire");
        if (villager.getLeashed() || villager.ridingEntity != null) report.fail("Target must be unmounted and unleashed");
        if (villager.hurtTime > 0 || villager.getHealth() < villager.getMaxHealth()) report.fail("Target is hurt or not at full health");
        if (!calibrated && (!villager.getActivePotionEffects().isEmpty() || villager.getDataWatcher().getWatchableObjectInt(7) != 0))
            report.fail("Wait for initial status effects to expire");
        if (calibrated) for (Object effect : villager.getActivePotionEffects()) {
            if (((PotionEffect)effect).getPotionID() != Potion.regeneration.id) report.fail("Unmodeled status effect on target");
        }
        AxisAlignedBB floor = villager.boundingBox.copy().offset(0, -0.05, 0);
        if (mc.theWorld.getCollidingBoundingBoxes(villager, floor).isEmpty()) report.fail("No solid ground beneath target");
        if (!dimensionReady(mc.theWorld, villager)) report.fail("Use Nether or dry daytime Overworld in a rain-capable biome");
        else report.pass("Supported dimension/weather/daylight branch");
        List<?> nearby = mc.theWorld.getEntitiesWithinAABBExcludingEntity(villager, villager.boundingBox.expand(8, 3, 8));
        int interference = 0;
        for (Object object : nearby) {
            Entity e = (Entity)object;
            if (e != mc.thePlayer && (e instanceof EntityLiving || e instanceof EntityPlayer)) interference++;
        }
        if (interference > 0) report.fail(interference + " other living entities in the checked AI area (8 x 3 x 8)");
        else report.pass("No other living entities in the checked AI area");
        if (scanBlocks) blocks(report, mc.theWorld, villager);
        if (!roster.ready()) report.fail("Exactly 12 online players required; observed " + roster.names().size());
        else report.pass("12-player roster recorded");
        if (offers.isEmpty()) report.fail("Merchant offers have not arrived");
        else try {
            report.budget = new ResourceBudget(offers, calibrated, armed);
            if (!OfferData.complete(offers)) {
                OfferData last = offers.get(offers.size() - 1);
                if (!armed && last.disabled) report.fail("Last offer is disabled");
                if (!calibrated && last.price > 5) report.fail("Calibration requires a last offer costing at most five items");
                if (!calibrated) report.unknown("Hidden stock: calibration needs seven remaining uses");
            }
            Map<Integer, Integer> counts = inventory(mc);
            for (Map.Entry<Integer, Integer> e : report.budget.payments.entrySet()) {
                int have = counts.containsKey(e.getKey()) ? counts.get(e.getKey()) : 0;
                String text = itemName(e.getKey()) + ": available " + have + ", required " + e.getValue()
                    + ", missing " + Math.max(0, e.getValue() - have);
                if (have < e.getValue()) report.fail(text); else report.pass(text);
            }
            int empty = 0;
            for (ItemStack stack : mc.thePlayer.inventory.mainInventory) if (stack == null) empty++;
            if (empty < report.budget.outputSlots) report.fail("Need " + report.budget.outputSlots + " empty inventory slots; have " + empty);
            else report.pass("Temporary output space: " + empty + " slots");
        } catch (IllegalArgumentException invalid) { report.fail(invalid.getMessage()); }
        if (mc.thePlayer.inventory.getItemStack() != null) report.fail("Cursor must be empty");
        report.unknown("Server village state is not synchronized to the client");
        report.unknown("Distant shared Random consumers and remote respawns cannot be excluded immediately");
        return report;
    }

    static boolean dimensionReady(World world, EntityVillager target) {
        if (world.provider.dimensionId == -1) return true;
        int x = MathHelper.floor_double(target.posX), z = MathHelper.floor_double(target.posZ);
        return world.provider.dimensionId == 0 && world.isDaytime() && !world.isRaining()
            && world.getBiomeGenForCoords(x, z).canSpawnLightningBolt();
    }
    private static void blocks(Report report, World world, EntityVillager target) {
        int x = MathHelper.floor_double(target.posX), y = MathHelper.floor_double(target.posY), z = MathHelper.floor_double(target.posZ);
        Map<String, Integer> count = new LinkedHashMap<String, Integer>();
        List<String> positions = new ArrayList<String>();
        int missing = 0;
        for (int cx = (x - 16) >> 4; cx <= (x + 16) >> 4; cx++) {
            for (int cz = (z - 16) >> 4; cz <= (z + 16) >> 4; cz++) {
                if (!world.getChunkProvider().chunkExists(cx, cz)) { missing++; continue; }
                for (int bx = Math.max(x - 16, cx * 16); bx <= Math.min(x + 16, cx * 16 + 15); bx++)
                    for (int bz = Math.max(z - 16, cz * 16); bz <= Math.min(z + 16, cz * 16 + 15); bz++)
                        for (int by = Math.max(0, y - 8); by <= Math.min(255, y + 8); by++) {
                            Block block = world.getBlock(bx, by, bz);
                            String name = block.getMaterial() == Material.water ? "Water" : block.getMaterial() == Material.lava ? "Lava"
                                : block == Blocks.fire ? "Fire" : null;
                            if (name != null) {
                                count.put(name, count.containsKey(name) ? count.get(name) + 1 : 1);
                                if (positions.size() < 8) positions.add(name + " (" + bx + ", " + by + ", " + bz + ")");
                            }
                        }
            }
        }
        if (!count.isEmpty()) report.fail("Conservative block scan +/-16 horizontal, +/-8 vertical: " + count + "; " + positions);
        else report.pass("No Water/Lava/Fire in loaded scan area (+/-16 horizontal, +/-8 vertical)");
        if (missing > 0) report.fail("Block scan incomplete: " + missing + " local chunks not loaded");
    }
    static Map<Integer, Integer> inventory(Minecraft mc) {
        Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
        for (ItemStack stack : mc.thePlayer.inventory.mainInventory) if (stack != null) {
            int id = Item.getIdFromItem(stack.getItem());
            counts.put(id, (counts.containsKey(id) ? counts.get(id) : 0) + stack.stackSize);
        }
        return counts;
    }
    static String itemName(int id) { return new ItemStack(Item.getItemById(id)).getDisplayName(); }
}
