/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.Experimental;
import lol.trq.orpheus.Track;

/** The ability to use the library of a source: likes, playlists, search and playing an item. */
@Experimental
public interface LibraryAccess {
    /** Likes or unlikes the track the session is playing. */
    CompletableFuture<ControlResult> like(String sessionId, boolean liked);

    CompletableFuture<Reply<List<Playlist>>> playlists();

    CompletableFuture<Reply<List<Track>>> search(String query, int limit);

    /** Plays a track, album or playlist by its uri in the source. */
    CompletableFuture<ControlResult> play(String sessionId, String uri);
}
