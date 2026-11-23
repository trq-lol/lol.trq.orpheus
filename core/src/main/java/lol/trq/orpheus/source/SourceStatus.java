/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

/**
 * Whether a source is working.
 *
 * @param state the state
 * @param reason why, for {@link State#UNAVAILABLE}; otherwise empty
 */
public record SourceStatus(State state, String reason) {
    public enum State {
        STARTING,
        READY,
        UNAVAILABLE,
        STOPPED
    }

    public static final SourceStatus STARTING = new SourceStatus(State.STARTING, "");
    public static final SourceStatus READY = new SourceStatus(State.READY, "");
    public static final SourceStatus STOPPED = new SourceStatus(State.STOPPED, "");

    public static SourceStatus unavailable(String reason) {
        return new SourceStatus(State.UNAVAILABLE, reason == null ? "" : reason);
    }
}
