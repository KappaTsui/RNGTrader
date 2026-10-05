package rngtrader.core;

/** Two ordered command-completion replies bracket the intervening Close Window handler. */
public final class CloseReceipt {
    private long before = ServerTickClock.UNKNOWN, after = ServerTickClock.UNKNOWN;
    public void reply(long tick) {
        if (tick == ServerTickClock.UNKNOWN) throw new IllegalStateException("Close receipt has no synchronized tick");
        if (before == ServerTickClock.UNKNOWN) before = tick;
        else if (after == ServerTickClock.UNKNOWN && tick >= before) after = tick;
        else throw new IllegalStateException("Unexpected close receipt ordering");
    }
    public boolean complete() { return after != ServerTickClock.UNKNOWN; }
    public TickInterval interval() {
        if (!complete()) throw new IllegalStateException("Incomplete close receipt");
        return new TickInterval(before, after);
    }
}
