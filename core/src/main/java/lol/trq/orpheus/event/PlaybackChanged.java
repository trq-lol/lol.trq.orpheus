/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.event;

import lol.trq.orpheus.Snapshot;

/** Any field of the playback changed. Raised after the more specific events of the same change. */
public record PlaybackChanged(Snapshot snapshot) implements OrpheusEvent {}
