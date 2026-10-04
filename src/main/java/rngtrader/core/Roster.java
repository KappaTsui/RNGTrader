package rngtrader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Vanilla S38 insertion order, with periodic server-order heartbeat reconciliation. */
public final class Roster {
    private final List<String> order = new ArrayList<String>();
    private final List<String> heartbeat = new ArrayList<String>();
    private long lastPacket = Long.MIN_VALUE, revision;
    private boolean collecting;
    private String source = "initial player list";

    public synchronized boolean observe(String name, boolean online, long nanos) {
        boolean changed = false;
        if (!online) {
            changed = order.remove(name);
            collecting = false; heartbeat.clear();
            if (changed) source = "leave";
        } else if (!order.contains(name)) {
            order.add(name); changed = true; source = "join/player-list snapshot";
            collecting = false; heartbeat.clear();
        } else {
            // Vanilla sends one existing player per tick in server-list order, then remains silent.
            if (lastPacket != Long.MIN_VALUE && nanos - lastPacket > 2_000_000_000L) {
                collecting = true; heartbeat.clear();
            }
            if (collecting) {
                if (heartbeat.contains(name)) { collecting = false; heartbeat.clear(); }
                else heartbeat.add(name);
                if (collecting && heartbeat.size() == order.size()) {
                    if (!heartbeat.equals(order) && new HashSet<String>(heartbeat).equals(new HashSet<String>(order))) {
                        order.clear(); order.addAll(heartbeat); changed = true;
                        source = "periodic server player-list order";
                    }
                    collecting = false; heartbeat.clear();
                }
            }
        }
        lastPacket = nanos;
        if (changed) revision++;
        return changed;
    }

    public synchronized List<String> names() { return Collections.unmodifiableList(new ArrayList<String>(order)); }
    public synchronized long revision() { return revision; }
    public synchronized String source() { return source; }
    public synchronized boolean ready() { return order.size() == 12; }
    public synchronized void clear() { order.clear(); heartbeat.clear(); collecting = false; lastPacket = Long.MIN_VALUE; revision++; }

    public static int[] decode(List<String> roster, List<String> sample) {
        if (roster.size() != 12 || sample.size() != 12 || new HashSet<String>(roster).size() != 12
            || new HashSet<String>(sample).size() != 12 || !new HashSet<String>(sample).equals(new HashSet<String>(roster))) {
            throw new IllegalArgumentException("A complete fixed 12-player permutation is required");
        }
        int[] permutation = new int[12];
        for (int i = 0; i < 12; i++) permutation[i] = roster.indexOf(sample.get(i));
        return ShuffleRng.decode(permutation);
    }
}
