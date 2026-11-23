/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import lol.trq.orpheus.source.ControlRequest;

/**
 * Playback commands for one session. Nothing here throws or blocks: every method returns a future that always
 * completes, with {@link ControlStatus#UNSUPPORTED} when the session cannot do it and {@link ControlStatus#REFUSED}
 * when the session is gone.
 */
public final class Controls {
    private final Orpheus orpheus;
    private final Playback bound;

    Controls(Orpheus orpheus, Playback bound) {
        this.orpheus = orpheus;
        this.bound = bound;
    }

    public CompletableFuture<ControlResult> play() {
        return send(Control.PLAY, 0, 0, false, Repeat.OFF);
    }

    public CompletableFuture<ControlResult> pause() {
        return send(Control.PAUSE, 0, 0, false, Repeat.OFF);
    }

    /** Plays when paused and pauses when playing. */
    public CompletableFuture<ControlResult> togglePlayPause() {
        return send(Control.TOGGLE, 0, 0, false, Repeat.OFF);
    }

    public CompletableFuture<ControlResult> stop() {
        return send(Control.STOP, 0, 0, false, Repeat.OFF);
    }

    public CompletableFuture<ControlResult> next() {
        return send(Control.NEXT, 0, 0, false, Repeat.OFF);
    }

    public CompletableFuture<ControlResult> previous() {
        return send(Control.PREVIOUS, 0, 0, false, Repeat.OFF);
    }

    /** Jumps to a position from the start of the track. */
    public CompletableFuture<ControlResult> seek(Duration position) {
        return send(Control.SEEK, Math.max(0, position.toMillis()), 0, false, Repeat.OFF);
    }

    public CompletableFuture<ControlResult> fastForward() {
        return send(Control.FAST_FORWARD, 0, 0, false, Repeat.OFF);
    }

    public CompletableFuture<ControlResult> rewind() {
        return send(Control.REWIND, 0, 0, false, Repeat.OFF);
    }

    public CompletableFuture<ControlResult> shuffle(boolean on) {
        return send(Control.SHUFFLE, 0, 0, on, Repeat.OFF);
    }

    public CompletableFuture<ControlResult> repeat(Repeat mode) {
        return send(Control.REPEAT, 0, 0, false, mode);
    }

    /** Sets the playback speed; 1 is normal. */
    public CompletableFuture<ControlResult> rate(double rate) {
        return send(Control.RATE, 0, rate, false, Repeat.OFF);
    }

    /** Sets the volume of the session, 0 to 1. */
    public CompletableFuture<ControlResult> volume(double volume) {
        return send(Control.VOLUME, 0, Math.max(0, Math.min(1, volume)), false, Repeat.OFF);
    }

    public CompletableFuture<ControlResult> mute(boolean muted) {
        return send(Control.MUTE, 0, 0, muted, Repeat.OFF);
    }

    private CompletableFuture<ControlResult> send(
            Control control, long position, double value, boolean on, Repeat repeat) {
        return orpheus.send(bound, new ControlRequest("", control, position, value, on, repeat));
    }
}
