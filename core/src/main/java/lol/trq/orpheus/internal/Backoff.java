/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import java.util.function.DoubleSupplier;

/** Retry delays that double from a base to a limit, with up to 20% random spread. Not thread-safe. Not API. */
public final class Backoff {
    private final long baseMillis;
    private final long maxMillis;
    private final DoubleSupplier random;
    private long next;

    public Backoff(long baseMillis, long maxMillis, DoubleSupplier random) {
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
        this.random = random;
        this.next = baseMillis;
    }

    public Backoff() {
        this(500, 30_000, Math::random);
    }

    /** The delay before the next attempt. Each call doubles the one after it. */
    public long nextMillis() {
        long delay = next;
        next = Math.min(maxMillis, next * 2);
        return delay + (long) (delay * 0.2 * random.getAsDouble());
    }

    public void reset() {
        next = baseMillis;
    }
}
