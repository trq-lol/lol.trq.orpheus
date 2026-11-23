/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.Experimental;

/** The ability to view and edit the play queue. */
@Experimental
public interface QueueAccess {
    CompletableFuture<Reply<List<QueueItem>>> queue(String sessionId);

    /** Moves an entry so that it plays right after {@code afterItemId}, or first when that is null. */
    CompletableFuture<ControlResult> move(String sessionId, String itemId, String afterItemId);

    CompletableFuture<ControlResult> add(String sessionId, String uri);

    CompletableFuture<ControlResult> remove(String sessionId, String itemId);
}
