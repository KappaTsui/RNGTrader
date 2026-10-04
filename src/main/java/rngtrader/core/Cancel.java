package rngtrader.core;

import java.util.concurrent.CancellationException;

final class Cancel {
    static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Session cancelled");
    }
}
