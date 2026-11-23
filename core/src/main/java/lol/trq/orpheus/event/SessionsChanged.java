/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.event;

import java.util.List;
import lol.trq.orpheus.SessionInfo;
import lol.trq.orpheus.Snapshot;

/**
 * The set of sessions changed.
 *
 * @param snapshot the current snapshot
 * @param sessions every known session, visible or hidden
 */
public record SessionsChanged(Snapshot snapshot, List<SessionInfo> sessions) implements OrpheusEvent {}
