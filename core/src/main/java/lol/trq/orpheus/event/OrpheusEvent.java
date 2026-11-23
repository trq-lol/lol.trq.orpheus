/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.event;

import lol.trq.orpheus.Snapshot;

/** Something changed. Every event carries the snapshot that was current when it was raised. */
public sealed interface OrpheusEvent
        permits PlaybackChanged,
                TrackChanged,
                StateChanged,
                Seeked,
                CoverReady,
                LyricsChanged,
                SessionsChanged,
                SourceStatusChanged {
    Snapshot snapshot();
}
