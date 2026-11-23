/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import lol.trq.orpheus.source.QueueAccess;
import lol.trq.orpheus.source.QueueItem;
import lol.trq.orpheus.source.Reply;

/** The play queue of the selected session. Completes {@link ControlStatus#UNSUPPORTED} where there is none. */
@Experimental
public final class PlayQueue {
    private final Orpheus orpheus;

    PlayQueue(Orpheus orpheus) {
        this.orpheus = orpheus;
    }

    public CompletableFuture<Reply<List<QueueItem>>> items() {
        return orpheus.ask(QueueAccess.class, List.of(), QueueAccess::queue);
    }

    /** Moves an entry so that it plays right after {@code afterItemId}, or first when that is null. */
    public CompletableFuture<ControlResult> move(String itemId, String afterItemId) {
        return orpheus.act(QueueAccess.class, (queue, session) -> queue.move(session, itemId, afterItemId));
    }

    public CompletableFuture<ControlResult> add(String uri) {
        return orpheus.act(QueueAccess.class, (queue, session) -> queue.add(session, uri));
    }

    public CompletableFuture<ControlResult> remove(String itemId) {
        return orpheus.act(QueueAccess.class, (queue, session) -> queue.remove(session, itemId));
    }
}
