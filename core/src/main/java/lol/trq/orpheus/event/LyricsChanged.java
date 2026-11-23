/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.event;

import lol.trq.orpheus.Snapshot;

/** Lyrics for the playing track were found, replaced or cleared. */
public record LyricsChanged(Snapshot snapshot) implements OrpheusEvent {}
