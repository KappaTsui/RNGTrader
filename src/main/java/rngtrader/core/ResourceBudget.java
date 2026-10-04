package rngtrader.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gross remaining payments; future receipts are deliberately not credited. */
public final class ResourceBudget {
    public final Map<Integer, Integer> payments = new LinkedHashMap<Integer, Integer>();
    public final int outputSlots;

    public ResourceBudget(List<OfferData> offers, boolean calibrated, boolean armed) {
        if (offers.isEmpty()) throw new IllegalArgumentException("No offers received");
        int known = OfferData.mask(offers);
        if (OfferData.complete(offers)) { outputSlots = 0; return; }
        if ((known & (1 << BlacksmithRng.Kind.IRON_INGOT.ordinal())) != 0) {
            throw new IllegalArgumentException("Iron already exists before the final position");
        }
        OfferData last = offers.get(offers.size() - 1);
        int repeats = armed ? 0 : calibrated ? 1 : 7;
        add(last.paymentItem(), last.price * repeats);
        for (BlacksmithRng.Kind kind : BlacksmithRng.Kind.values()) {
            if (kind == BlacksmithRng.Kind.IRON_INGOT || (known & (1 << kind.ordinal())) != 0) continue;
            add(kind.buy ? kind.item : 388, kind.max - 1);
        }
        // Gear cannot stack. Currency output fits one reserved slot even when its existing stack is full.
        outputSlots = !calibrated && !last.kind.buy ? 7 : 1;
    }

    private void add(int item, int count) {
        if (count > 0) payments.put(item, required(item) + count);
    }
    public int required(int item) { Integer n = payments.get(item); return n == null ? 0 : n; }
    public Map<Integer, Integer> missing(Map<Integer, Integer> inventory) {
        Map<Integer, Integer> result = new LinkedHashMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> e : payments.entrySet()) {
            int have = inventory.containsKey(e.getKey()) ? inventory.get(e.getKey()) : 0;
            if (have < e.getValue()) result.put(e.getKey(), e.getValue() - have);
        }
        return result;
    }
}
