/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import lol.trq.orpheus.DisplayRules;

/**
 * Something that knows what is playing. A source owns its threads; none of its methods may block the caller.
 *
 * <p>Optional abilities are separate interfaces ({@link Transport}, {@link QueueAccess}, {@link Devices},
 * {@link LibraryAccess}) handed out by {@link #capability(Class)}.
 */
public interface Source {
    /** A short stable name, unique among the sources of one {@code Orpheus}. */
    String id();

    /** Starts reporting to {@code sink}. Returns quickly; failures are reported as a status, not thrown. */
    void start(SourceSink sink);

    /** Stops and releases every thread, handle and process. Safe to call twice and before {@code start}. */
    void stop();

    /**
     * Whether the sessions of this source belong to other applications and must pass the app rules. True for
     * operating system sources, false for a program's own player.
     */
    default boolean foreign() {
        return false;
    }

    /** Rules that come with the data of this source. */
    default DisplayRules display() {
        return DisplayRules.NONE;
    }

    /** The ability of the given type, or null when this source does not have it. */
    default <T> T capability(Class<T> type) {
        return type.isInstance(this) ? type.cast(this) : null;
    }
}
