/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import lol.trq.orpheus.Experimental;

/**
 * A playlist in the library of a source.
 *
 * @param uri what to pass to {@link LibraryAccess#play}
 * @param name a display name
 * @param size the number of tracks, or -1 when unknown
 */
@Experimental
public record Playlist(String uri, String name, int size) {}
