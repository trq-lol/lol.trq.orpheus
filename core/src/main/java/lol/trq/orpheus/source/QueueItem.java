/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import lol.trq.orpheus.Track;

/**
 * An entry in a play queue.
 *
 * @param id identifies the entry for moving and removing
 * @param track the queued track
 */
public record QueueItem(String id, Track track) {}
