/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.windows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import lol.trq.orpheus.internal.Backoff;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.source.Transport;

/**
 * The media sessions of Windows 10 (1809 and later) and Windows 11: whatever shows up in the system media overlay.
 * One session per application.
 *
 * <p>Uses a small native library bundled in the jar. If it cannot be loaded (wrong system, blocked, missing), the
 * source reports itself unavailable with the reason and nothing else happens. On Java 24 and later the JVM prints a
 * warning about native access unless started with {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p>Windows gives a media kind, but browsers report everything as music, and private browser windows publish
 * sessions too. Per-application volume is not part of this Windows interface, so volume and mute are unsupported.
 */
public final class WindowsSessions implements Source, Transport {
    private static final int AWAIT_MILLIS = 60_000;
    private static final int MAX_THUMBNAILS = 16;
    private static final Control[] CONTROLS = Control.values();

    private final Object lifecycle = new Object();
    private final Map<Long, CompletableFuture<ControlResult>> pending = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private Thread thread;
    private boolean stopping;
    private long handle;

    private WindowsSessions() {}

    public static WindowsSessions create() {
        return new WindowsSessions();
    }

    @Override
    public String id() {
        return "windows";
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
            thread = new Thread(() -> run(sink), "orpheus-windows");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public void stop() {
        Thread ending;
        synchronized (lifecycle) {
            ending = thread;
            thread = null;
            stopping = true;
            if (handle != 0) {
                Bridge.invoke(Bridge.OP_CLOSE, handle, null);
                handle = 0;
            }
            lifecycle.notifyAll();
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
        for (CompletableFuture<ControlResult> future : pending.values()) {
            future.complete(ControlResult.failed(reason));
        }
        pending.clear();
    }

    private void run(SourceSink sink) {
        sink.status(SourceStatus.STARTING);
        String failure = Bridge.load();
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
                byte[] opened = Bridge.invoke(Bridge.OP_OPEN, 0, null);
                current = opened == null
                        ? 0
                        : ByteBuffer.wrap(opened).order(ByteOrder.LITTLE_ENDIAN).getLong();
                handle = current;
            }
            Reader reader = new Reader(sink, backoff);
            while (current != 0 && !reader.failed) {
                byte[] blob = Bridge.invoke(Bridge.OP_AWAIT, current, timeout);
                if (blob == null) {
                    break;
                }
                Blob.read(blob, reader);
            }
            failPending("the media session service stopped");
            synchronized (lifecycle) {
                if (stopping) {
                    return;
                }
                if (handle != 0) {
                    Bridge.invoke(Bridge.OP_CLOSE, handle, null);
                    handle = 0;
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
    private final class Reader implements Blob.Handler {
        private final SourceSink sink;
        private final Backoff backoff;
        private final Map<Long, CoverData> thumbnails = new LinkedHashMap<>(16, 0.75f, true);
        private final Map<Integer, Long> resumedAt = new HashMap<>();
        private final Map<Integer, Boolean> wasPlaying = new HashMap<>();
        boolean failed;

        Reader(SourceSink sink, Backoff backoff) {
            this.sink = sink;
            this.backoff = backoff;
        }

        @Override
        public void sessions(long builtNanosAgo, List<Blob.Session> sessions) {
            long built = System.nanoTime() - builtNanosAgo;
            List<SessionUpdate> updates = new ArrayList<>(sessions.size());
            Set<Integer> alive = new java.util.HashSet<>();
            for (Blob.Session s : sessions) {
                alive.add(s.id());
                boolean playing = s.status() == 1;
                Boolean before = wasPlaying.put(s.id(), playing);
                if (playing && before != null && !before) {
                    resumedAt.put(s.id(), built);
                }
                long measured = built - s.ageMillis() * 1_000_000L;
                Long resumed = resumedAt.get(s.id());
                if (resumed != null && resumed - measured > 0) {
                    // the application did not refresh its timeline when it resumed: the position stood still until then
                    measured = resumed;
                }
                Set<Control> controls = EnumSet.noneOf(Control.class);
                for (int bit = 0; bit < CONTROLS.length; bit++) {
                    if ((s.controls() & 1 << bit) != 0) {
                        controls.add(CONTROLS[bit]);
                    }
                }
                String name = s.app().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")
                        ? s.app().substring(0, s.app().length() - 4)
                        : s.app();
                updates.add(new SessionUpdate(
                        Integer.toString(s.id()),
                        new SourceApp(s.app(), name),
                        new Track(
                                null,
                                s.title(),
                                s.artist().isEmpty() ? List.of() : List.of(s.artist()),
                                s.album(),
                                s.albumArtist(),
                                s.durationMillis(),
                                s.trackNumber(),
                                null,
                                switch (s.kind()) {
                                    case 1 -> MediaKind.MUSIC;
                                    case 2 -> MediaKind.VIDEO;
                                    case 3 -> MediaKind.OTHER;
                                    default -> MediaKind.UNKNOWN;
                                }),
                        switch (s.status()) {
                            case 1 -> PlaybackState.PLAYING;
                            case 2 -> PlaybackState.PAUSED;
                            case 3 -> PlaybackState.BUFFERING;
                            default -> PlaybackState.STOPPED;
                        },
                        s.positionMillis(),
                        measured,
                        Double.isNaN(s.rate()) || s.rate() <= 0 ? 1 : s.rate(),
                        s.shuffle() == 1 ? Shuffle.ON : s.shuffle() == 0 ? Shuffle.OFF : Shuffle.UNKNOWN,
                        switch (s.repeat()) {
                            case 0 -> Repeat.OFF;
                            case 1 -> Repeat.ONE;
                            case 2 -> Repeat.ALL;
                            default -> Repeat.UNKNOWN;
                        },
                        Double.NaN,
                        controls,
                        thumbnails.get(s.thumbnail()),
                        null));
            }
            wasPlaying.keySet().retainAll(alive);
            resumedAt.keySet().retainAll(alive);
            sink.sessions(updates);
        }

        @Override
        public void thumbnail(long key, byte[] bytes) {
            thumbnails.put(key, new CoverData(Long.toHexString(key) + "-" + bytes.length, bytes));
            if (thumbnails.size() > MAX_THUMBNAILS) {
                thumbnails.remove(thumbnails.keySet().iterator().next());
            }
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
                backoff.reset();
                sink.status(SourceStatus.READY);
            } else {
                failed = true;
                sink.status(SourceStatus.unavailable(reason));
            }
        }
    }

    @Override
    public CompletableFuture<ControlResult> send(ControlRequest request) {
        if (request.control() == Control.VOLUME || request.control() == Control.MUTE) {
            return CompletableFuture.completedFuture(
                    ControlResult.unsupported("Windows media sessions have no volume control"));
        }
        int session;
        try {
            session = Integer.parseInt(request.sessionId());
        } catch (NumberFormatException e) {
            return CompletableFuture.completedFuture(ControlResult.refused("the session is gone"));
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
            if (Bridge.invoke(Bridge.OP_COMMAND, handle, argument) == null) {
                pending.remove(id);
                future.complete(ControlResult.failed("not running"));
            }
        }
        return future;
    }
}
