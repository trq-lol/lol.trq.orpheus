/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/** The state of a playback session. */
public enum PlaybackState {
    PLAYING,
    PAUSED,
    STOPPED,
    BUFFERING,
    /** No session is selected. */
    NONE
}
