package rngtrader.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerMerchant;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import rngtrader.core.OfferData;

/** One acknowledged inventory action at a time, with provenance for newly purchased gear. */
final class TradeExecutor {
    interface Sink {
        void select(int index);
        void complete(List<TraderService.Sound> sounds, boolean calibratedBatch);
        void fail(String message);
        void log(String event, Object... values);
        long lastSoundTime();
        long pickupRevision();
    }
    private enum Step { CLEAR, PAY, PRIME, RESULT, DROP, DONE }
    private final Minecraft mc;
    private final Sink sink;
    private final OfferData offer;
    private final int index, repeats, window;
    private final boolean calibration;
    private final Map<Integer, ItemStack> purchased = new LinkedHashMap<Integer, ItemStack>();
    private final List<TraderService.Sound> sounds = new ArrayList<TraderService.Sound>();
    private Step step = Step.CLEAR;
    private short action;
    private boolean pending, accepted, resultPending, failed, collecting, stopping;
    private long sent, settledAt, resultAt, pickupRevision;
    private int sourceSlot = -1;
    private ItemStack[] resultBefore;

    TradeExecutor(Minecraft mc, Sink sink, OfferData offer, int index, boolean calibration) {
        this.mc = mc; this.sink = sink; this.offer = offer; this.index = index;
        this.calibration = calibration; repeats = calibration ? 7 : 1;
        window = container().windowId;
    }
    private Container container() { return mc.thePlayer.openContainer; }
    private ItemStack slot(int i) { return container().getSlot(i).getStack(); }
    private int id(ItemStack s) { return s == null ? -1 : Item.getIdFromItem(s.getItem()); }
    boolean finished() { return failed || step == Step.DONE; }
    boolean hasPendingClick() { return pending; }
    boolean paymentEmpty() { return slot(0) == null && slot(1) == null && mc.thePlayer.inventory.getItemStack() == null; }
    boolean collectingSounds() { return collecting; }
    void stop() { stopping = true; purchased.clear(); }
    void settleStopped(long now) {
        if (!stopping) return;
        if (pending && ((settledAt != 0 && now >= settledAt) || now - sent > 3_000_000_000L)) pending = false;
        if (!pending) { collecting = false; failed = true; }
    }

    void sound(TraderService.Sound sound) { if (collecting && sound.time >= resultAt && sound.name.equals("mob.villager.yes")) sounds.add(sound); }
    void confirm(int id, short number, boolean ok, long at) {
        if (!pending || id != window || number != action) return;
        accepted = ok; settledAt = at + 100_000_000L;
    }
    private void error(String message) { failed = true; purchased.clear(); sink.fail(message); }

    void tick(long now) {
        if (stopping) { settleStopped(now); return; }
        if (finished()) return;
        if (!(container() instanceof ContainerMerchant) || container().windowId != window) { error("Merchant container changed during transaction"); return; }
        if (pending) {
            if (settledAt == 0) {
                if (now - sent > 3_000_000_000L) error("Inventory confirmation timeout; no click retry was sent");
                return;
            }
            if (now < settledAt) return;
            pending = false;
            if (!accepted) { error("Inventory transaction rejected; waiting for Vanilla resynchronization"); return; }
            if (resultPending) {
                resultPending = false; collecting = false;
                if (sink.pickupRevision() != pickupRevision) { error("An item pickup overlapped the trade; output ownership is ambiguous"); return; }
                if (sounds.size() != repeats) {
                    // Vanilla's acknowledgment compares the first returned stack, not the number of shift-click iterations.
                    // Reconstruct only the observed trades so partially exhausted stock cannot leave predicted ghost gear.
                    if (sounds.size() < repeats) reconcilePartialBatch(sounds.size());
                    error("Expected " + repeats + " confirmed trade sounds, received " + sounds.size() + "; hidden stock may be insufficient"); return;
                }
                for (Map.Entry<Integer, ItemStack> e : purchased.entrySet()) {
                    if (!ItemStack.areItemStacksEqual(slot(e.getKey()), e.getValue())) { error("Purchased output changed before verification"); return; }
                }
                sink.log("trade", "kind", offer.kind.name(), "price", offer.price, "count", repeats,
                    "window", window, "action", action, "sounds", sounds);
                // Inference starts immediately so subsequent GUI probes cannot be lost while dropping gear.
                sink.complete(new ArrayList<TraderService.Sound>(sounds), calibration);
                step = Step.DROP;
            }
        }
        if (failed) return;
        switch (step) {
            case CLEAR:
                if (mc.thePlayer.inventory.getItemStack() != null) { error("Cursor is not empty"); break; }
                if (slot(0) != null) { click(0, 0, 1); break; }
                if (slot(1) != null) { click(1, 0, 1); break; }
                sink.select(index); step = Step.PAY;
                break;
            case PAY:
                pay(); break;
            case PRIME:
                if (now - sink.lastSoundTime() < 650_000_000L) step = Step.RESULT;
                else if (now - sent > 150_000_000L) { sink.select(index); sent = now; }
                break;
            case RESULT:
                if (id(slot(2)) != offer.resultItem()) { error("Server did not expose the selected trade output"); break; }
                if (mc.thePlayer.inventory.getItemStack() != null) { error("Cursor must be empty before taking the output"); break; }
                ItemStack[] before = new ItemStack[39];
                for (int i = 0; i < 39; i++) before[i] = ItemStack.copyItemStack(slot(i));
                resultBefore = before;
                pickupRevision = sink.pickupRevision(); resultAt = now; collecting = true; resultPending = true;
                click(2, 0, 1);
                if (!offer.kind.buy) {
                    for (int i = 3; i < 39; i++) {
                        ItemStack after = slot(i);
                        if (before[i] == null && id(after) == offer.resultItem() && after.stackSize == 1) purchased.put(i, after.copy());
                    }
                    if (purchased.size() != repeats) error("Insufficient isolated output space");
                }
                break;
            case DROP:
                if (sink.pickupRevision() != pickupRevision && !purchased.isEmpty()) { error("Inventory pickup invalidated remaining output ownership"); break; }
                if (!purchased.isEmpty()) {
                    Map.Entry<Integer, ItemStack> output = purchased.entrySet().iterator().next();
                    if (!ItemStack.areItemStacksEqual(slot(output.getKey()), output.getValue())) { error("Output slot no longer contains the purchased item"); break; }
                    int slot = output.getKey(); purchased.remove(slot);
                    sink.log("drop_purchased_output", "slot", slot, "item", offer.resultItem());
                    click(slot, 1, 4);
                } else {
                    if (!paymentEmpty()) { error("Unexpected remaining payment or cursor item"); break; }
                    step = Step.DONE;
                }
                break;
            default: break;
        }
    }

    private void reconcilePartialBatch(int count) {
        ContainerMerchant c = (ContainerMerchant)container();
        for (int i = 3; i < 39; i++) c.getSlot(i).putStack(ItemStack.copyItemStack(resultBefore[i]));
        c.getSlot(0).putStack(ItemStack.copyItemStack(resultBefore[0]));
        c.getSlot(1).putStack(ItemStack.copyItemStack(resultBefore[1]));
        c.setCurrentRecipeIndex(index);
        net.minecraft.village.MerchantRecipe recipe = c.getMerchantInventory().getCurrentRecipe();
        for (int i = 0; i < count; i++) c.transferStackInSlot(mc.thePlayer, 2);
        if (recipe != null) recipe.func_82785_h();
        c.getMerchantInventory().resetRecipeAndSlots();
    }

    private void pay() {
        ItemStack payment = slot(0), cursor = mc.thePlayer.inventory.getItemStack();
        if (payment != null && id(payment) != offer.paymentItem()) { error("Unexpected payment slot contents"); return; }
        int remaining = repeats * offer.price - (payment == null ? 0 : payment.stackSize);
        if (remaining < 0) { error("Payment exceeds the planned trade count"); return; }
        if (remaining == 0) {
            if (cursor != null) {
                if (sourceSlot < 3 || slot(sourceSlot) != null) { error("Cannot return remaining payment to its original slot"); return; }
                click(sourceSlot, 0, 0);
            } else step = Step.PRIME;
            return;
        }
        if (cursor != null) {
            if (id(cursor) != offer.paymentItem()) { error("Unexpected cursor item"); return; }
            click(0, cursor.stackSize <= remaining ? 0 : 1, 0);
            return;
        }
        sourceSlot = -1;
        for (int i = 3; i < 39; i++) if (id(slot(i)) == offer.paymentItem()) { sourceSlot = i; break; }
        if (sourceSlot < 0) { error("Required payment items are missing"); return; }
        click(sourceSlot, 0, 0);
    }

    private void click(int slot, int button, int mode) {
        Container c = container();
        action = c.getNextTransactionID(mc.thePlayer.inventory);
        ItemStack expected = c.slotClick(slot, button, mode, mc.thePlayer);
        pending = true; settledAt = 0; accepted = false; sent = System.nanoTime();
        mc.thePlayer.sendQueue.addToSendQueue(new C0EPacketClickWindow(window, slot, button, mode, expected, action));
        sink.log("click", "window", window, "slot", slot, "button", button, "mode", mode, "action", action);
    }
}
