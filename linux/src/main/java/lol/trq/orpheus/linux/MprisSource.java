/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.MediaKind;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.SourceApp;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.cover.CoverDecoder;
import lol.trq.orpheus.internal.Backoff;
import lol.trq.orpheus.linux.dbus.Bus;
import lol.trq.orpheus.linux.dbus.BusAddress;
import lol.trq.orpheus.linux.dbus.Message;
import lol.trq.orpheus.linux.dbus.Variant;
import lol.trq.orpheus.linux.dbus.WireException;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.source.Transport;

/**
 * Media players on the Linux session bus, through the MPRIS interface. One session per player.
 *
 * <p>Everything is event driven except the position, which MPRIS never announces: it is read when the track, state or
 * rate changes and every five seconds while a player is playing. All work runs on one thread owned by this source.
 * MPRIS gives no media kind, so a browser playing a video looks like any other player.
 */
public final class MprisSource implements Source, Transport {
    private static final String PREFIX = "org.mpris.MediaPlayer2.";
    private static final String OBJECT = "/org/mpris/MediaPlayer2";
    private static final String ROOT = "org.mpris.MediaPlayer2";
    private static final String PLAYER = "org.mpris.MediaPlayer2.Player";
    private static final String PROPERTIES = "org.freedesktop.DBus.Properties";
    private static final String DBUS = "org.freedesktop.DBus";
    private static final String DBUS_PATH = "/org/freedesktop/DBus";
    private static final String NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack";
    private static final long CALL_TIMEOUT_MILLIS = 3000;
    private static final long POSITION_INTERVAL_MILLIS = 5000;

    private static final class Player {
        final String name;
        String owner = "";
        String identity = "";
        final Map<Object, Object> properties = new HashMap<>();
        long positionMicros;
        long positionNanos = System.nanoTime();
        String artUrl;
        CoverData art;

        Player(String name) {
            this.name = name;
        }
    }

    private final Map<String, String> environment;
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Backoff backoff = new Backoff();
    private final Object lifecycle = new Object();
    private ScheduledExecutorService worker;
    private Thread workerThread;
    private volatile SourceSink sink;
    private Bus bus;
    private boolean publishQueued;

    private MprisSource(Map<String, String> environment) {
        this.environment = environment;
    }

    public static MprisSource create() {
        return new MprisSource(System.getenv());
    }

    /** Uses the given variables instead of the process environment to find the bus. */
    public static MprisSource create(Map<String, String> environment) {
        return new MprisSource(Map.copyOf(environment));
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
    public void start(SourceSink newSink) {
        synchronized (lifecycle) {
            if (worker != null) {
                return;
            }
            sink = newSink;
            publishQueued = false;
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "orpheus-mpris");
                thread.setDaemon(true);
                workerThread = thread;
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            worker = executor;
            newSink.status(SourceStatus.STARTING);
            run(this::connect);
            worker.scheduleWithFixedDelay(
                    guarded(this::refreshPositions),
                    POSITION_INTERVAL_MILLIS,
                    POSITION_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService ending;
        Thread endingThread;
        synchronized (lifecycle) {
            ending = worker;
            endingThread = workerThread;
            worker = null;
            workerThread = null;
            sink = null;
        }
        if (ending == null) {
            return;
        }
        try {
            ending.submit(() -> {
                        if (bus != null) {
                            bus.close();
                            bus = null;
                        }
                        players.clear();
                    })
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // the worker is being shut down regardless
        }
        ending.shutdownNow();
        try {
            ending.awaitTermination(5, TimeUnit.SECONDS);
            if (endingThread != null && endingThread != Thread.currentThread()) {
                endingThread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // one bad message must not end the worker
            }
        };
    }

    private boolean run(Runnable task) {
        ScheduledExecutorService current = worker;
        if (current != null) {
            try {
                current.execute(guarded(task));
                return true;
            } catch (RejectedExecutionException e) {
                // stopping
            }
        }
        return false;
    }

    private void later(Runnable task, long millis) {
        ScheduledExecutorService current = worker;
        if (current != null) {
            try {
                current.schedule(guarded(task), millis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // stopping
            }
        }
    }

    private void status(SourceStatus status) {
        SourceSink current = sink;
        if (current != null) {
            current.status(status);
        }
    }

    // ---- connection

    private void connect() {
        String socket;
        try {
            socket = BusAddress.sessionSocket(environment);
        } catch (WireException e) {
            status(SourceStatus.unavailable(e.getMessage()));
            return;
        }
        try {
            Bus[] holder = new Bus[1];
            Bus connected = Bus.connect(
                    socket, message -> run(() -> received(holder[0], message)), () -> run(() -> lost(holder[0])));
            holder[0] = connected;
            bus = connected;
        } catch (IOException e) {
            status(SourceStatus.unavailable(e.getMessage()));
            later(this::connect, backoff.nextMillis());
            return;
        }
        Bus current = bus;
        call(Message.call(DBUS, DBUS_PATH, DBUS, "Hello", ""))
                .thenAccept(hello -> run(() -> {
                    if (current != bus) {
                        return;
                    }
                    if (hello == null || hello.type() != Message.RETURN) {
                        lost(current);
                        return;
                    }
                    String ownerChanged =
                            "type='signal',sender='org.freedesktop.DBus',interface='org.freedesktop.DBus',"
                                    + "member='NameOwnerChanged',arg0namespace='org.mpris.MediaPlayer2'";
                    call(Message.call(DBUS, DBUS_PATH, DBUS, "AddMatch", "s", ownerChanged));
                    call(Message.call(DBUS, DBUS_PATH, DBUS, "AddMatch", "s", "type='signal',path='" + OBJECT + "'"));
                    call(Message.call(DBUS, DBUS_PATH, DBUS, "ListNames", ""))
                            .thenAccept(names -> run(() -> {
                                if (current != bus) {
                                    return;
                                }
                                if (names == null || names.type() != Message.RETURN) {
                                    lost(current);
                                    return;
                                }
                                backoff.reset();
                                status(SourceStatus.READY);
                                if (!names.body().isEmpty() && names.body().get(0) instanceof List<?> list) {
                                    for (Object name : list) {
                                        if (name instanceof String s) {
                                            add(s);
                                        }
                                    }
                                }
                                publish();
                            }));
                }));
    }

    private void lost(Bus which) {
        if (which != bus || which == null) {
            return;
        }
        which.close();
        bus = null;
        players.clear();
        status(SourceStatus.unavailable("the session bus connection ended"));
        later(this::connect, backoff.nextMillis());
    }

    /** A call that always completes: with the reply, or with null on failure or silence. */
    private CompletableFuture<Message> call(Message message) {
        Bus current = bus;
        CompletableFuture<Message> result = new CompletableFuture<>();
        if (current == null) {
            result.complete(null);
            return result;
        }
        CompletableFuture<Message> reply = current.call(message);
        reply.whenComplete((value, error) -> result.complete(error == null ? value : null));
        later(() -> result.complete(null), CALL_TIMEOUT_MILLIS);
        result.whenComplete((value, error) -> reply.cancel(false));
        return result;
    }

    // ---- players

    private void add(String name) {
        if (!name.startsWith(PREFIX) || name.equals(PREFIX + "playerctld") || players.containsKey(name)) {
            return;
        }
        Player player = new Player(name);
        players.put(name, player);
        call(Message.call(DBUS, DBUS_PATH, DBUS, "GetNameOwner", "s", name))
                .thenAccept(reply -> run(() -> {
                    if (reply != null && reply.type() == Message.RETURN) {
                        player.owner = reply.text();
                    }
                }));
        call(Message.call(name, OBJECT, PROPERTIES, "GetAll", "s", ROOT))
                .thenAccept(reply -> run(() -> {
                    if (properties(reply).get("Identity") instanceof String identity) {
                        player.identity = identity;
                        publish();
                    }
                }));
        loadAll(player);
    }

    private void loadAll(Player player) {
        call(Message.call(player.name, OBJECT, PROPERTIES, "GetAll", "s", PLAYER))
                .thenAccept(reply -> run(() -> {
                    if (players.get(player.name) != player) {
                        return;
                    }
                    Map<?, ?> all = properties(reply);
                    player.properties.putAll(all);
                    if (all.get("Position") instanceof Long micros) {
                        position(player, micros);
                    }
                    publish();
                }));
    }

    private static Map<?, ?> properties(Message reply) {
        if (reply != null
                && reply.type() == Message.RETURN
                && !reply.body().isEmpty()
                && reply.body().get(0) instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private void position(Player player, long micros) {
        player.positionMicros = micros;
        player.positionNanos = System.nanoTime();
    }

    private static long currentMicros(Player player) {
        if (!"Playing".equals(player.properties.get("PlaybackStatus"))) {
            return player.positionMicros;
        }
        double rate = player.properties.get("Rate") instanceof Number r ? r.doubleValue() : 1;
        return player.positionMicros + (long) ((System.nanoTime() - player.positionNanos) / 1000 * rate);
    }

    private void readPosition(Player player) {
        long asked = System.nanoTime();
        call(Message.call(player.name, OBJECT, PROPERTIES, "Get", "ss", PLAYER, "Position"))
                .thenAccept(reply -> run(() -> {
                    if (players.get(player.name) != player || reply == null || reply.type() != Message.RETURN) {
                        return;
                    }
                    if (!reply.body().isEmpty() && reply.body().get(0) instanceof Long micros) {
                        long now = System.nanoTime();
                        player.positionMicros = micros;
                        player.positionNanos = asked + (now - asked) / 2;
                        publish();
                    }
                }));
    }

    private void refreshPositions() {
        for (Player player : players.values()) {
            if ("Playing".equals(player.properties.get("PlaybackStatus"))) {
                readPosition(player);
            }
        }
    }

    private void received(Bus from, Message message) {
        if (from != bus || message.type() != Message.SIGNAL || message.member() == null) {
            return;
        }
        List<Object> body = message.body();
        if (message.member().equals("NameOwnerChanged") && DBUS.equals(message.iface()) && body.size() == 3) {
            String name = String.valueOf(body.get(0));
            if (String.valueOf(body.get(2)).isEmpty()) {
                if (players.remove(name) != null) {
                    publish();
                }
            } else {
                players.remove(name);
                add(name);
            }
            return;
        }
        Player player = null;
        for (Player candidate : players.values()) {
            if (candidate.owner.equals(message.sender())) {
                player = candidate;
            }
        }
        if (player == null) {
            return;
        }
        if (message.member().equals("Seeked") && !body.isEmpty() && body.get(0) instanceof Long micros) {
            position(player, micros);
            publish();
        } else if (message.member().equals("PropertiesChanged")
                && body.size() == 3
                && PLAYER.equals(body.get(0))
                && body.get(1) instanceof Map<?, ?> changed) {
            boolean moved = changed.containsKey("PlaybackStatus")
                    || changed.containsKey("Metadata")
                    || changed.containsKey("Rate");
            if (moved) {
                // freeze the position where it is now: the new state must not start from an old measurement
                position(player, currentMicros(player));
            }
            player.properties.putAll(changed);
            if (body.get(2) instanceof List<?> invalidated && !invalidated.isEmpty()) {
                loadAll(player);
            } else if (moved) {
                readPosition(player);
            }
            publish();
        }
    }

    // ---- reporting

    private void publish() {
        if (!publishQueued) {
            publishQueued = true;
            run(() -> {
                publishQueued = false;
                SourceSink current = sink;
                if (current == null) {
                    return;
                }
                List<SessionUpdate> sessions = new ArrayList<>();
                for (Player player : players.values()) {
                    if (!player.properties.isEmpty()) {
                        sessions.add(session(player));
                    }
                }
                current.sessions(sessions);
            });
        }
    }

    private SessionUpdate session(Player player) {
        Map<Object, Object> p = player.properties;
        Map<?, ?> metadata = p.get("Metadata") instanceof Map<?, ?> m ? m : Map.of();
        String suffix = player.name.substring(PREFIX.length());
        int instance = suffix.indexOf(".instance");
        String appId = instance > 0 ? suffix.substring(0, instance) : suffix;
        long lengthMicros = metadata.get("mpris:length") instanceof Number n ? n.longValue() : -1;
        Track track = new Track(
                trackId(metadata),
                text(metadata.get("xesam:title")),
                strings(metadata.get("xesam:artist")),
                text(metadata.get("xesam:album")),
                String.join(", ", strings(metadata.get("xesam:albumArtist"))),
                lengthMicros > 0 ? lengthMicros / 1000 : -1,
                metadata.get("xesam:trackNumber") instanceof Number n ? n.intValue() : 0,
                metadata.get("xesam:url") instanceof String url ? url : null,
                MediaKind.UNKNOWN);
        PlaybackState state =
                switch (String.valueOf(p.get("PlaybackStatus"))) {
                    case "Playing" -> PlaybackState.PLAYING;
                    case "Paused" -> PlaybackState.PAUSED;
                    default -> PlaybackState.STOPPED;
                };
        String artUrl = metadata.get("mpris:artUrl") instanceof String url && !url.isEmpty() ? url : null;
        if (artUrl == null || !artUrl.equals(player.artUrl)) {
            player.artUrl = artUrl;
            player.art = artUrl == null ? null : loadArt(artUrl);
        }
        return new SessionUpdate(
                player.name,
                new SourceApp(appId, player.identity.isEmpty() ? appId : player.identity),
                track,
                state,
                player.positionMicros / 1000,
                player.positionNanos,
                p.get("Rate") instanceof Number rate && rate.doubleValue() > 0 ? rate.doubleValue() : 1,
                p.get("Shuffle") instanceof Boolean on ? (on ? Shuffle.ON : Shuffle.OFF) : Shuffle.UNKNOWN,
                repeat(p.get("LoopStatus")),
                p.get("Volume") instanceof Number volume ? Math.max(0, Math.min(1, volume.doubleValue())) : Double.NaN,
                controls(p),
                player.art,
                player.art == null ? artUrl : null);
    }

    private static Set<Control> controls(Map<Object, Object> p) {
        Set<Control> controls = new HashSet<>();
        if (Boolean.FALSE.equals(p.get("CanControl"))) {
            return controls;
        }
        boolean play = Boolean.TRUE.equals(p.get("CanPlay"));
        boolean pause = Boolean.TRUE.equals(p.get("CanPause"));
        if (play) {
            controls.add(Control.PLAY);
        }
        if (pause) {
            controls.add(Control.PAUSE);
        }
        if (play || pause) {
            controls.add(Control.TOGGLE);
        }
        controls.add(Control.STOP);
        if (Boolean.TRUE.equals(p.get("CanGoNext"))) {
            controls.add(Control.NEXT);
        }
        if (Boolean.TRUE.equals(p.get("CanGoPrevious"))) {
            controls.add(Control.PREVIOUS);
        }
        if (Boolean.TRUE.equals(p.get("CanSeek"))) {
            controls.add(Control.SEEK);
        }
        if (p.get("Shuffle") instanceof Boolean) {
            controls.add(Control.SHUFFLE);
        }
        if (p.get("LoopStatus") instanceof String) {
            controls.add(Control.REPEAT);
        }
        if (p.get("MinimumRate") instanceof Number min
                && p.get("MaximumRate") instanceof Number max
                && max.doubleValue() > min.doubleValue()) {
            controls.add(Control.RATE);
        }
        if (p.get("Volume") instanceof Number) {
            controls.add(Control.VOLUME);
        }
        return controls;
    }

    private static Repeat repeat(Object loop) {
        if (!(loop instanceof String s)) {
            return Repeat.UNKNOWN;
        }
        return switch (s) {
            case "Track" -> Repeat.ONE;
            case "Playlist" -> Repeat.ALL;
            default -> Repeat.OFF;
        };
    }

    private static String trackId(Map<?, ?> metadata) {
        return metadata.get("mpris:trackid") instanceof String id && !id.equals(NO_TRACK) ? id : null;
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    out.add(s);
                }
            }
        } else if (value instanceof String s) {
            out.add(s);
        }
        return out;
    }

    /** Loads art from a local file or an inline data uri. Anything else is left for the caller as a uri. */
    static CoverData loadArt(String url) {
        try {
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) {
                    return null;
                }
                if (url.length() - comma > CoverDecoder.MAX_ENCODED_BYTES * 4L / 3 + 4) {
                    return null;
                }
                return CoverData.of(Base64.getMimeDecoder().decode(url.substring(comma + 1)));
            }
            if (url.startsWith("file:")) {
                Path path = Path.of(URI.create(url));
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }
                // A player chooses this path, so it is treated as hostile: read a bounded amount whatever the
                // file claims to be, and keep it only when it starts like an image.
                byte[] data;
                try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    data = in.readNBytes(CoverDecoder.MAX_ENCODED_BYTES + 1);
                }
                return data.length <= CoverDecoder.MAX_ENCODED_BYTES && looksLikeAnImage(data)
                        ? CoverData.of(data)
                        : null;
            }
        } catch (IOException | RuntimeException e) {
            // unreadable art is no art
        }
        return null;
    }

    private static boolean looksLikeAnImage(byte[] d) {
        if (d.length < 8) {
            return false;
        }
        boolean png = (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
        boolean jpeg = (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
        boolean gif = d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8';
        boolean bmp = d[0] == 'B' && d[1] == 'M';
        return png || jpeg || gif || bmp;
    }

    // ---- commands

    @Override
    public CompletableFuture<ControlResult> send(ControlRequest request) {
        CompletableFuture<ControlResult> result = new CompletableFuture<>();
        if (worker == null) {
            result.complete(ControlResult.failed("not started"));
            return result;
        }
        boolean queued = run(() -> {
            Player player = players.get(request.sessionId());
            if (player == null) {
                result.complete(ControlResult.refused("the player left the bus"));
                return;
            }
            Message message = message(player, request);
            if (message == null) {
                result.complete(ControlResult.unsupported("MPRIS has no " + request.control()));
                return;
            }
            call(message).thenAccept(reply -> {
                if (reply == null) {
                    result.complete(ControlResult.failed("no answer from the player"));
                } else if (reply.type() == Message.RETURN) {
                    result.complete(ControlResult.DONE);
                } else if (String.valueOf(reply.error()).endsWith("UnknownMethod")
                        || String.valueOf(reply.error()).endsWith("NotSupported")) {
                    result.complete(ControlResult.unsupported(reply.text()));
                } else {
                    result.complete(ControlResult.refused(reply.error() + ": " + reply.text()));
                }
            });
        });
        if (!queued) {
            result.complete(ControlResult.failed("not started"));
        }
        return result;
    }

    private static Message message(Player player, ControlRequest request) {
        String name = player.name;
        return switch (request.control()) {
            case PLAY -> Message.call(name, OBJECT, PLAYER, "Play", "");
            case PAUSE -> Message.call(name, OBJECT, PLAYER, "Pause", "");
            case TOGGLE -> Message.call(name, OBJECT, PLAYER, "PlayPause", "");
            case STOP -> Message.call(name, OBJECT, PLAYER, "Stop", "");
            case NEXT -> Message.call(name, OBJECT, PLAYER, "Next", "");
            case PREVIOUS -> Message.call(name, OBJECT, PLAYER, "Previous", "");
            case SEEK -> seek(player, request.positionMillis());
            case SHUFFLE -> set(name, "Shuffle", new Variant("b", request.on()));
            case REPEAT ->
                set(
                        name,
                        "LoopStatus",
                        new Variant(
                                "s",
                                switch (request.repeat()) {
                                    case ONE -> "Track";
                                    case ALL -> "Playlist";
                                    default -> "None";
                                }));
            case RATE -> set(name, "Rate", new Variant("d", request.value()));
            case VOLUME -> set(name, "Volume", new Variant("d", request.value()));
            default -> null;
        };
    }

    private static Message seek(Player player, long targetMillis) {
        Map<?, ?> metadata = player.properties.get("Metadata") instanceof Map<?, ?> m ? m : Map.of();
        String id = trackId(metadata);
        if (id != null && id.startsWith("/")) {
            return Message.call(player.name, OBJECT, PLAYER, "SetPosition", "ox", id, targetMillis * 1000);
        }
        long nowMicros = currentMicros(player);
        return Message.call(player.name, OBJECT, PLAYER, "Seek", "x", targetMillis * 1000 - nowMicros);
    }

    private static Message set(String name, String property, Variant value) {
        return Message.call(name, OBJECT, PROPERTIES, "Set", "ssv", PLAYER, property, value);
    }
}
