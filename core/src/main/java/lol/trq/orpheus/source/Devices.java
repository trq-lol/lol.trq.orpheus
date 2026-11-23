/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.Experimental;

/** The ability to list output devices and move playback between them. */
@Experimental
public interface Devices {
    CompletableFuture<Reply<List<Device>>> devices(String sessionId);

    CompletableFuture<ControlResult> transfer(String sessionId, String deviceId);
}
