/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import lol.trq.orpheus.source.SourceStatus;

/**
 * One configured source, for a "where should the music come from" picker.
 *
 * @param id the source id, as passed to {@link Orpheus#pin(String)}
 * @param status whether it is working, and why not
 * @param sessions how many sessions it reports right now, hidden ones included
 */
public record SourceInfo(String id, SourceStatus status, int sessions) {}
