package rngtrader.core;

import java.util.Arrays;

/** Deduplicates cached status replies and brackets each five-second update. */
public final class SampleClock {
    public static final class Sample {
        public final int[] draws;
        public final long earliest, latest;
        public final boolean contiguous;
        Sample(int[] draws, long earliest, long latest, boolean contiguous) {
            this.draws = draws.clone(); this.earliest = earliest; this.latest = latest; this.contiguous = contiguous;
        }
    }
    private int[] previous;
    private long previousStart, lastChange;
    public Sample accept(int[] draws, long sent, long received) {
        Sample event = null;
        if (received < sent || received - sent > 250_000_000L) throw new IllegalArgumentException("Status latency exceeds timing margin");
        if (previous != null && !Arrays.equals(previous, draws)) {
            long elapsed = received - lastChange;
            event = new Sample(draws, previousStart, received,
                lastChange != 0 && elapsed >= 4_400_000_000L && elapsed <= 5_600_000_000L);
            lastChange = received;
        } else if (lastChange != 0 && received - lastChange > 5_700_000_000L) {
            throw new IllegalArgumentException("Missing or indistinguishable status update");
        }
        previous = draws.clone(); previousStart = sent;
        return event;
    }
    public void reset() { previous = null; previousStart = lastChange = 0; }
}
