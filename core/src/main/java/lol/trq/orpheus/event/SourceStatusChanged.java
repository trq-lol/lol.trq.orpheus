/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.event;

import lol.trq.orpheus.Snapshot;
import lol.trq.orpheus.source.SourceStatus;

/**
 * A source became ready, unavailable or stopped.
 *
 * @param snapshot the current snapshot
 * @param sourceId the source
 * @param status its new status
 */
public record SourceStatusChanged(Snapshot snapshot, String sourceId, SourceStatus status) implements OrpheusEvent {}
