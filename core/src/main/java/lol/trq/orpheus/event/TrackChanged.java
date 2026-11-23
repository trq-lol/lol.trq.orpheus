/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.event;

import lol.trq.orpheus.Snapshot;

/** A different track, or a different session, is now selected. */
public record TrackChanged(Snapshot snapshot) implements OrpheusEvent {}
