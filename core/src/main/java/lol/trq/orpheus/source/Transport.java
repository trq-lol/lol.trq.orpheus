/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import java.util.concurrent.CompletableFuture;
import lol.trq.orpheus.ControlResult;

/** The ability to send playback commands. */
public interface Transport {
    /** Sends a command. Never throws and never blocks; the future always completes. */
    CompletableFuture<ControlResult> send(ControlRequest request);
}
