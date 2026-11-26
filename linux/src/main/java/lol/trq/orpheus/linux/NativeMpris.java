/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.MediaKind;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.SourceApp;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.internal.AppNames;
import lol.trq.orpheus.internal.Backoff;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.source.Transport;

/**
 * Media players on the Linux session bus, through the MPRIS interface, read by a small native library bundled in
 * the jar. Reports the same sessions, ids and controls as {@link MprisSource}.
 *
 * <p>If the library cannot be loaded (another architecture, blocked, missing), the source reports itself unavailable
 * with the reason; use {@link MprisSource} there. The library needs no system library but the C library. On Java 24
 * and later the JVM prints a warning about native access unless started with
 * {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class NativeMpris implements Source, Transport {
    private static final int AWAIT_MILLIS = 60_000;
    private static final long HEALTHY_NANOS = 30_000_000_000L;
    private static final Control[] CONTROLS = Control.values();

    private final Object lifecycle = new Object();
    private final Map<Long, CompletableFuture<ControlResult>> pending = new ConcurrentHashMap<>();
    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private Thread thread;
    private boolean stopping;
    private long handle;

    private NativeMpris() {}

    public static NativeMpris create() {
        return new NativeMpris();
    }

    /** Whether the native library loads on this system; null when it does, otherwise the reason it does not. */
    public static String unavailableReason() {
        return NativeBridge.load();
    }

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
        synchronized (lifecycle) {
            if (thread != null) {
                return;
            }
            stopping = false;
            thread = new Thread(() -> run(sink), "orpheus-mpris");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public void stop() {
        Thread ending;
        long closing;
        synchronized (lifecycle) {
            ending = thread;
            thread = null;
            stopping = true;
            closing = handle;
            handle = 0;
            lifecycle.notifyAll();
        }
        if (closing != 0) {
            NativeBridge.invoke(NativeBridge.OP_CLOSE, closing, null);
        }
        if (ending != null && ending != Thread.currentThread()) {
            try {
                ending.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        failPending("stopped");
    }

    private void failPending(String reason) {
        for (Long request : pending.keySet()) {
            CompletableFuture<ControlResult> future = pending.remove(request);
            if (future != null) {
                future.complete(ControlResult.failed(reason));
            }
        }
    }

    private void run(SourceSink sink) {
        sink.status(SourceStatus.STARTING);
        String failure = NativeBridge.load();
        if (failure != null) {
            sink.status(SourceStatus.unavailable(failure));
            return;
        }
        Backoff backoff = new Backoff();
        byte[] timeout = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(AWAIT_MILLIS)
                .array();
        while (true) {
            long current;
            synchronized (lifecycle) {
                if (stopping) {
                    return;
                }
                byte[] opened = NativeBridge.invoke(NativeBridge.OP_OPEN, 0, null);
                current = opened == null
                        ? 0
                        : ByteBuffer.wrap(opened).order(ByteOrder.LITTLE_ENDIAN).getLong();
                handle = current;
            }
            long openedAt = System.nanoTime();
            Reader reader = new Reader(sink);
            while (current != 0 && !reader.failed) {
                byte[] blob = NativeBridge.invoke(NativeBridge.OP_AWAIT, current, timeout);
                if (blob == null) {
                    break;
                }
                NativeBlob.read(blob, reader);
            }
            failPending("the session bus connection ended");
            long closing;
            synchronized (lifecycle) {
                closing = handle;
                handle = 0;
            }
            if (closing != 0) {
                NativeBridge.invoke(NativeBridge.OP_CLOSE, closing, null);
            }
            ids.clear();
            if (System.nanoTime() - openedAt > HEALTHY_NANOS) {
                backoff.reset();
            }
            synchronized (lifecycle) {
                if (stopping) {
                    return;
                }
                sink.sessions(List.of());
                try {
                    lifecycle.wait(backoff.nextMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Turns records into reports. Lives for one native handle. */
    private final class Reader implements NativeBlob.Handler {
        private final SourceSink sink;
        private final Map<String, String> artUrls = new HashMap<>();
        private final Map<String, CoverData> art = new HashMap<>();
        boolean failed;

        Reader(SourceSink sink) {
            this.sink = sink;
        }

        @Override
        public void sessions(long builtNanosAgo, List<NativeBlob.Session> sessions) {
            long built = System.nanoTime() - builtNanosAgo;
            List<SessionUpdate> updates = new ArrayList<>(sessions.size());
            Set<String> present = new HashSet<>();
            for (NativeBlob.Session s : sessions) {
                present.add(s.name());
                ids.put(s.name(), s.id());
                Set<Control> controls = new HashSet<>();
                for (int bit = 0; bit < CONTROLS.length; bit++) {
                    if ((s.controls() & 1 << bit) != 0) {
                        controls.add(CONTROLS[bit]);
                    }
                }
                String artUrl = s.artUrl().isEmpty() ? null : s.artUrl();
                if (artUrl == null) {
                    artUrls.remove(s.name());
                    art.remove(s.name());
                } else if (!artUrl.equals(artUrls.get(s.name()))) {
                    artUrls.put(s.name(), artUrl);
                    CoverData loaded = MprisSource.loadArt(artUrl);
                    if (loaded == null) {
                        art.remove(s.name());
                    } else {
                        art.put(s.name(), loaded);
                    }
                }
                CoverData cover = art.get(s.name());
                boolean remote = artUrl != null && (artUrl.startsWith("https:") || artUrl.startsWith("http:"));
                updates.add(new SessionUpdate(
                        s.name(),
                        new SourceApp(s.app(), s.identity().isEmpty() ? AppNames.humanize(s.app()) : s.identity()),
                        new Track(
                                s.trackId().isEmpty() ? null : s.trackId(),
                                s.title(),
                                s.artists(),
                                s.album(),
                                s.albumArtist(),
                                s.lengthMillis(),
                                s.trackNumber(),
                                s.url().isEmpty() ? null : s.url(),
                                MediaKind.UNKNOWN),
                        switch (s.status()) {
                            case 1 -> PlaybackState.PLAYING;
                            case 2 -> PlaybackState.PAUSED;
                            default -> PlaybackState.STOPPED;
                        },
                        s.positionMillis(),
                        built - s.ageNanos(),
                        Double.isNaN(s.rate()) || s.rate() <= 0 ? 1 : s.rate(),
                        s.shuffle() == 1 ? Shuffle.ON : s.shuffle() == 0 ? Shuffle.OFF : Shuffle.UNKNOWN,
                        switch (s.repeat()) {
                            case 0 -> Repeat.OFF;
                            case 1 -> Repeat.ONE;
                            case 2 -> Repeat.ALL;
                            default -> Repeat.UNKNOWN;
                        },
                        s.volume(),
                        controls,
                        cover,
                        cover == null && remote ? artUrl : null));
            }
            artUrls.keySet().retainAll(present);
            art.keySet().retainAll(present);
            ids.keySet().retainAll(present);
            sink.sessions(updates);
        }

        @Override
        public void result(long request, int status, String reason) {
            CompletableFuture<ControlResult> future = pending.remove(request);
            if (future != null) {
                future.complete(
                        switch (status) {
                            case 0 -> ControlResult.DONE;
                            case 1 -> ControlResult.refused(reason);
                            case 3 -> ControlResult.unsupported(reason);
                            default -> ControlResult.failed(reason);
                        });
            }
        }

        @Override
        public void status(boolean ready, String reason) {
            if (ready) {
                sink.status(SourceStatus.READY);
            } else {
                failed = true;
                sink.status(SourceStatus.unavailable(reason));
            }
        }
    }

    @Override
    public CompletableFuture<ControlResult> send(ControlRequest request) {
        if (request.control() == Control.MUTE) {
            return CompletableFuture.completedFuture(ControlResult.unsupported("MPRIS has no mute"));
        }
        Integer session = ids.get(request.sessionId());
        if (session == null) {
            return CompletableFuture.completedFuture(ControlResult.refused("the player left the bus"));
        }
        CompletableFuture<ControlResult> future = new CompletableFuture<>();
        long id = requests.incrementAndGet();
        byte[] argument = ByteBuffer.allocate(31)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(id)
                .putInt(session)
                .put((byte) request.control().ordinal())
                .putLong(request.positionMillis())
                .putDouble(request.value())
                .put((byte) (request.on() ? 1 : 0))
                .put((byte)
                        switch (request.repeat()) {
                            case ONE -> 1;
                            case ALL -> 2;
                            default -> 0;
                        })
                .array();
        synchronized (lifecycle) {
            if (handle == 0) {
                future.complete(ControlResult.failed("not running"));
                return future;
            }
            pending.put(id, future);
            if (NativeBridge.invoke(NativeBridge.OP_COMMAND, handle, argument) == null) {
                pending.remove(id);
                future.complete(ControlResult.failed("not running"));
            }
        }
        return future;
    }
}
