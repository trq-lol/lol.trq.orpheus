/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.event;

import lol.trq.orpheus.Snapshot;

/** The position jumped inside the same track. */
public record Seeked(Snapshot snapshot) implements OrpheusEvent {}
