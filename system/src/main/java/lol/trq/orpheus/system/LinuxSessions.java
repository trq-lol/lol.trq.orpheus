/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.system;

import java.util.concurrent.CompletableFuture;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.linux.MprisSource;
import lol.trq.orpheus.linux.NativeMpris;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.Transport;

/** MPRIS through the native library, or through the pure Java client when the library cannot load. */
final class LinuxSessions implements Source, Transport {
    private final Object lifecycle = new Object();
    private volatile Source active;

    @Override
    public String id() {
        return "mpris";
    }

    @Override
    public boolean foreign() {
        return true;
    }

    @Override
    public void start(SourceSink sink) {
        Source chosen;
        synchronized (lifecycle) {
            if (active != null) {
                return;
            }
            chosen = NativeMpris.unavailableReason() == null ? NativeMpris.create() : MprisSource.create();
            active = chosen;
        }
        chosen.start(sink);
    }

    @Override
    public void stop() {
        Source ending;
        synchronized (lifecycle) {
            ending = active;
            active = null;
        }
        if (ending != null) {
            ending.stop();
        }
    }

    @Override
    public CompletableFuture<ControlResult> send(ControlRequest request) {
        Source current = active;
        Transport transport = current == null ? null : current.capability(Transport.class);
        return transport == null
                ? CompletableFuture.completedFuture(ControlResult.failed("not started"))
                : transport.send(request);
    }
}
