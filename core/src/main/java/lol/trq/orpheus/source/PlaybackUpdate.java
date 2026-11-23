/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import java.time.Duration;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.Track;

/** What a program tells a {@link PushSource} about its own player. Immutable; each method returns a new value. */
public final class PlaybackUpdate {
    final Track track;
    final PlaybackState state;
    final long positionMillis;
    final long measuredNanos;
    final double rate;
    final Shuffle shuffle;
    final Repeat repeat;
    final double volume;
    final CoverData cover;
    final String coverUri;

    private PlaybackUpdate(
            Track track,
            PlaybackState state,
            long positionMillis,
            long measuredNanos,
            double rate,
            Shuffle shuffle,
            Repeat repeat,
            double volume,
            CoverData cover,
            String coverUri) {
        this.track = track;
        this.state = state;
        this.positionMillis = positionMillis;
        this.measuredNanos = measuredNanos;
        this.rate = rate;
        this.shuffle = shuffle;
        this.repeat = repeat;
        this.volume = volume;
        this.cover = cover;
        this.coverUri = coverUri;
    }

    public static PlaybackUpdate playing(Track track) {
        return of(track, PlaybackState.PLAYING);
    }

    public static PlaybackUpdate paused(Track track) {
        return of(track, PlaybackState.PAUSED);
    }

    public static PlaybackUpdate stopped() {
        return of(Track.NONE, PlaybackState.STOPPED);
    }

    public static PlaybackUpdate of(Track track, PlaybackState state) {
        return new PlaybackUpdate(
                track, state, 0, System.nanoTime(), 1, Shuffle.UNKNOWN, Repeat.UNKNOWN, Double.NaN, null, null);
    }

    /** The position right now. */
    public PlaybackUpdate position(Duration position) {
        return position(position, System.nanoTime());
    }

    /** The position as it was at {@code nanoTime}, a {@link System#nanoTime()} reading. */
    public PlaybackUpdate position(Duration position, long nanoTime) {
        return new PlaybackUpdate(
                track, state, position.toMillis(), nanoTime, rate, shuffle, repeat, volume, cover, coverUri);
    }

    public PlaybackUpdate rate(double newRate) {
        return new PlaybackUpdate(
                track, state, positionMillis, measuredNanos, newRate, shuffle, repeat, volume, cover, coverUri);
    }

    public PlaybackUpdate shuffle(boolean on) {
        return new PlaybackUpdate(
                track,
                state,
                positionMillis,
                measuredNanos,
                rate,
                on ? Shuffle.ON : Shuffle.OFF,
                repeat,
                volume,
                cover,
                coverUri);
    }

    public PlaybackUpdate repeat(Repeat mode) {
        return new PlaybackUpdate(
                track, state, positionMillis, measuredNanos, rate, shuffle, mode, volume, cover, coverUri);
    }

    public PlaybackUpdate volume(double newVolume) {
        return new PlaybackUpdate(
                track, state, positionMillis, measuredNanos, rate, shuffle, repeat, newVolume, cover, coverUri);
    }

    /** Encoded image bytes (PNG, JPEG, BMP, GIF). Pass the same {@link CoverData} again while the image is unchanged. */
    public PlaybackUpdate cover(CoverData data) {
        return new PlaybackUpdate(
                track, state, positionMillis, measuredNanos, rate, shuffle, repeat, volume, data, coverUri);
    }

    /** A cover location for the caller to load; the library does not fetch it. */
    public PlaybackUpdate coverUri(String uri) {
        return new PlaybackUpdate(
                track, state, positionMillis, measuredNanos, rate, shuffle, repeat, volume, cover, uri);
    }
}
