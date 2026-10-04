package rngtrader.core;

import java.util.List;

/** Only the fields actually sent in MC|TrList. Hidden use counters are not inferred here. */
public final class OfferData {
    public final BlacksmithRng.Kind kind;
    public final int price;
    public final boolean disabled;

    public OfferData(BlacksmithRng.Kind kind, int price, boolean disabled) {
        if (kind == null || price < kind.min || price >= kind.max) {
            throw new IllegalArgumentException("Unsupported Blacksmith offer or price");
        }
        this.kind = kind;
        this.price = price;
        this.disabled = disabled;
    }

    public int paymentItem() { return kind.buy ? kind.item : 388; }
    public int resultItem() { return kind.buy ? 388 : kind.item; }
    public boolean sameTrade(OfferData other) { return kind == other.kind && price == other.price; }

    public static int mask(List<OfferData> offers) {
        int mask = 0;
        for (OfferData offer : offers) {
            int bit = 1 << offer.kind.ordinal();
            if ((mask & bit) != 0) throw new IllegalArgumentException("Duplicate trade type");
            mask |= bit;
        }
        return mask;
    }

    public static boolean complete(List<OfferData> offers) {
        return offers.size() == 26 && Integer.bitCount(mask(offers)) == 26
            && offers.get(25).kind == BlacksmithRng.Kind.IRON_INGOT;
    }

    public static void verifyAppend(List<OfferData> before, List<OfferData> after) {
        if (after.size() != before.size() + 1) throw new IllegalStateException("Refresh did not append exactly one trade");
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).sameTrade(after.get(i))) throw new IllegalStateException("Existing trade changed during refresh");
        }
        mask(after);
        if ((after.get(after.size() - 1).kind == BlacksmithRng.Kind.IRON_INGOT) != (before.size() == 25)) {
            throw new IllegalStateException("Iron-last condition failed");
        }
    }

    @Override public String toString() { return kind + " @ " + price + (disabled ? " [disabled]" : ""); }
}
