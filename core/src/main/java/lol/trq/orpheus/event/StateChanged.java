/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.event;

import lol.trq.orpheus.Snapshot;

/** Playing, paused, stopped or buffering changed. */
public record StateChanged(Snapshot snapshot) implements OrpheusEvent {}
