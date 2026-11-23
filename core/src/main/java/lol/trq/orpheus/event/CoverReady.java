/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.event;

import lol.trq.orpheus.Snapshot;

/** The cover of the playing track finished decoding; {@code snapshot().cover()} is set. */
public record CoverReady(Snapshot snapshot) implements OrpheusEvent {}
