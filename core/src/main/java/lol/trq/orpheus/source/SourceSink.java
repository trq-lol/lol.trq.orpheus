/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import java.util.List;

/** Where a source reports. Both methods may be called from any thread and return at once. */
public interface SourceSink {
    void status(SourceStatus status);

    /** Replaces the whole session list of the source. */
    void sessions(List<SessionUpdate> sessions);
}
