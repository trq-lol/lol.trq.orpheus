/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import lol.trq.orpheus.source.Device;
import lol.trq.orpheus.source.Devices;
import lol.trq.orpheus.source.Reply;

/** The output devices of the selected session. Completes {@link ControlStatus#UNSUPPORTED} where there are none. */
@Experimental
public final class Outputs {
    private final Orpheus orpheus;

    Outputs(Orpheus orpheus) {
        this.orpheus = orpheus;
    }

    public CompletableFuture<Reply<List<Device>>> list() {
        return orpheus.ask(Devices.class, List.of(), Devices::devices);
    }

    public CompletableFuture<ControlResult> transfer(String deviceId) {
        return orpheus.act(Devices.class, (devices, session) -> devices.transfer(session, deviceId));
    }
}
