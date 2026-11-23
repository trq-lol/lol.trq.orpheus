/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/**
 * A position that can be advanced locally: where playback was at one instant and how fast it moves.
 *
 * @param positionMillis the position at {@code anchorNanos}
 * @param anchorNanos a {@link System#nanoTime()} reading
 * @param rate playback speed, 1 is normal
 * @param running whether the position is advancing
 * @param durationMillis the track length, or -1 when unknown
 */
public record Timeline(long positionMillis, long anchorNanos, double rate, boolean running, long durationMillis) {
    public static final Timeline NONE = new Timeline(0, 0, 1, false, -1);

    /** The position at {@code nanos}, a {@link System#nanoTime()} reading, clamped to the track. */
    public long positionMillisAt(long nanos) {
        long position = positionMillis;
        if (running) {
            position += (long) ((nanos - anchorNanos) * rate / 1_000_000.0);
        }
        if (position < 0) {
            return 0;
        }
        if (durationMillis >= 0 && position > durationMillis) {
            return durationMillis;
        }
        return position;
    }

    /** The fraction played at {@code nanos}, 0 to 1, or 0 when the length is unknown. */
    public float progressAt(long nanos) {
        if (durationMillis <= 0) {
            return 0;
        }
        return (float) ((double) positionMillisAt(nanos) / durationMillis);
    }

    public Timeline at(long newPositionMillis, long nanos) {
        return new Timeline(newPositionMillis, nanos, rate, running, durationMillis);
    }
}
