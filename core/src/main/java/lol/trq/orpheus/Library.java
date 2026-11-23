/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import lol.trq.orpheus.source.LibraryAccess;
import lol.trq.orpheus.source.Playlist;
import lol.trq.orpheus.source.Reply;

/**
 * The music library behind the selected session, or behind the first source when nothing is selected. Completes
 * {@link ControlStatus#UNSUPPORTED} where there is none.
 */
@Experimental
public final class Library {
    private final Orpheus orpheus;

    Library(Orpheus orpheus) {
        this.orpheus = orpheus;
    }

    /** Likes or unlikes the playing track. */
    public CompletableFuture<ControlResult> like(boolean liked) {
        return orpheus.act(LibraryAccess.class, (library, session) -> library.like(session, liked));
    }

    public CompletableFuture<Reply<List<Playlist>>> playlists() {
        return orpheus.ask(LibraryAccess.class, List.of(), (library, session) -> library.playlists());
    }

    public CompletableFuture<Reply<List<Track>>> search(String query, int limit) {
        return orpheus.ask(LibraryAccess.class, List.of(), (library, session) -> library.search(query, limit));
    }

    /** Plays a track, album or playlist by its uri in the source. */
    public CompletableFuture<ControlResult> play(String uri) {
        return orpheus.act(LibraryAccess.class, (library, session) -> library.play(session, uri));
    }
}
