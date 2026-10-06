package rngtrader.core;

/** A free merchant sound at an exact tick distance from the preceding sound. */
public final class RecoveryProbe {
    public final int pitch, ticks;

    public RecoveryProbe(int pitch, int ticks) {
        if (pitch < 50 || pitch > 75) throw new IllegalArgumentException("Adult pitch byte 50..75 required");
        if (ticks < 21 || ticks > 39) throw new IllegalArgumentException("Recovery probe interval must be 21..39 ticks");
        this.pitch = pitch;
        this.ticks = ticks;
    }
}
