/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BackoffTest {
    @Test
    void doublesUpToTheLimitAndResets() {
        Backoff backoff = new Backoff(500, 4000, () -> 0);
        assertEquals(500, backoff.nextMillis());
        assertEquals(1000, backoff.nextMillis());
        assertEquals(2000, backoff.nextMillis());
        assertEquals(4000, backoff.nextMillis());
        assertEquals(4000, backoff.nextMillis());
        backoff.reset();
        assertEquals(500, backoff.nextMillis());
    }

    @Test
    void spreadStaysWithinATwentyPercent() {
        assertEquals(600, new Backoff(500, 4000, () -> 1).nextMillis());
    }
}
