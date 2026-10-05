package rngtrader.core;

/** Inclusive server-processing tick interval. */
public final class TickInterval {
    public final long first, last;
    public TickInterval(long first, long last) {
        if (first == ServerTickClock.UNKNOWN || last < first) throw new IllegalArgumentException("Invalid tick interval");
        this.first = first; this.last = last;
    }
    public boolean contains(TickInterval other) { return first <= other.first && last >= other.last; }
    public int width() { return Math.toIntExact(last - first); }
    public String toString() { return first + ".." + last; }
}
