package rngtrader.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Single-worker API for the exact entity and shared-Random model. */
public final class RngEngine {
    private final BlacksmithRng.Tracker tracker = new BlacksmithRng.Tracker();
    private long revision;

    public void initialize(int[] pitches) { tracker.initialize(pitches); revision++; requireStates(); }
    public void observe(int pitch, int minimum, int maximum, boolean free) {
        tracker.observe(pitch, minimum, maximum, free); revision++; requireStates();
    }
    public void recoverShared(int[][] samples) { tracker.initializeShuffle(samples); revision++; }
    public void observeShared(int[] draws) { tracker.observeShuffle(draws); revision++; }
    public boolean timersReady() { return tracker.coarse == null && !tracker.states.isEmpty(); }
    public int stateCount() { return tracker.coarse != null ? tracker.coarse.length : tracker.states.size(); }
    public long revision() { return revision; }
    private void requireStates() {
        if (stateCount() == 0) throw new IllegalStateException("Observation excluded all entity models");
    }

    public int plan(int known, int[] disabledCounts, int minimum, int maximum, int jitter) {
        if (disabledCounts.length == 0) throw new IllegalArgumentException("No stock hypotheses");
        for (int wait = minimum; wait <= maximum; wait++) {
            boolean valid = true;
            for (int disabled : disabledCounts) {
                if (tracker.planNew(known, disabled, wait, wait, jitter) != wait) { valid = false; break; }
            }
            if (valid) return wait;
        }
        return -1;
    }

    public void resume(int size, int[] disabledCounts, int waitLow, int waitHigh,
                       int closedLow, int closedHigh, OfferData appended) {
        Map<String, BlacksmithRng.State> all = new LinkedHashMap<String, BlacksmithRng.State>();
        for (int disabled : disabledCounts) {
            BlacksmithRng.Tracker branch = new BlacksmithRng.Tracker();
            branch.states = new ArrayList<BlacksmithRng.State>(tracker.states);
            try {
                branch.resumeNew(size, disabled, waitLow, waitHigh, closedLow, closedHigh, appended.kind, appended.price);
                for (BlacksmithRng.State s : branch.states) all.put(s.key(), s);
            } catch (IllegalStateException excluded) { /* Another stock branch can still match. */ }
        }
        if (all.isEmpty()) throw new IllegalStateException("Refresh excluded all stock/timing models");
        tracker.states = new ArrayList<BlacksmithRng.State>(all.values());
        revision++;
    }

    public static long[] recoverSharedStates(int[][] samples) { return ShuffleRng.recover(samples); }
}
