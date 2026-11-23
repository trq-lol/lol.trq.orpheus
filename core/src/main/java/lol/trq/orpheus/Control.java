/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/** A playback command. Which ones work is reported per snapshot by {@link Playback#can(Control)}. */
public enum Control {
    PLAY,
    PAUSE,
    TOGGLE,
    STOP,
    NEXT,
    PREVIOUS,
    SEEK,
    FAST_FORWARD,
    REWIND,
    SHUFFLE,
    REPEAT,
    RATE,
    VOLUME,
    MUTE
}
