/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.util.concurrent.CompletableFuture;

/** Finds the tempo of a track. The library has no provider of its own. */
@FunctionalInterface
public interface BpmProvider {
    /**
     * The beats per minute of a track. Called once per track on a library thread and must not block. An answer for a
     * track that no longer plays is dropped.
     *
     * @return a future, or null when there is no answer; null, a failure or a value outside 20 to 400 means unknown
     */
    CompletableFuture<Float> find(Track track);
}
