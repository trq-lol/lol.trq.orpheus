/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimelineTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void advancesOnlyWhileRunning() {
        Timeline playing = new Timeline(10_000, 5 * SECOND, 1, true, 200_000);
        Timeline paused = new Timeline(10_000, 5 * SECOND, 1, false, 200_000);
        assertEquals(12_500, playing.positionMillisAt(5 * SECOND + 2_500_000_000L));
        assertEquals(10_000, paused.positionMillisAt(5 * SECOND + 2_500_000_000L));
    }

    @Test
    void followsThePlaybackRate() {
        Timeline fast = new Timeline(0, 0, 1.5, true, 200_000);
        Timeline slow = new Timeline(0, 0, 0.5, true, 200_000);
        assertEquals(15_000, fast.positionMillisAt(10 * SECOND));
        assertEquals(5_000, slow.positionMillisAt(10 * SECOND));
    }

    @Test
    void neverLeavesTheTrack() {
        Timeline timeline = new Timeline(190_000, 0, 1, true, 200_000);
        assertEquals(200_000, timeline.positionMillisAt(60 * SECOND));
        assertEquals(1f, timeline.progressAt(60 * SECOND));
        // a reading taken before the anchor, as after a clock adjustment, must not go negative
        assertEquals(0, new Timeline(1_000, 10 * SECOND, 1, true, 200_000).positionMillisAt(0));
    }

    @Test
    void unknownLengthIsNotClampedAndHasNoProgress() {
        Timeline live = new Timeline(0, 0, 1, true, -1);
        assertEquals(3_600_000, live.positionMillisAt(3600 * SECOND));
        assertEquals(0f, live.progressAt(3600 * SECOND));
    }

    @Test
    void survivesNanoTimeWrappingPastZero() {
        long anchor = Long.MAX_VALUE - SECOND;
        Timeline timeline = new Timeline(0, anchor, 1, true, 200_000);
        assertEquals(3_000, timeline.positionMillisAt(anchor + 3 * SECOND));
    }
}
