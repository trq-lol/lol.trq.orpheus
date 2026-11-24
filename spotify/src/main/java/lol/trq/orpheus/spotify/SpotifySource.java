/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.spotify;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.DisplayRules;
import lol.trq.orpheus.Experimental;
import lol.trq.orpheus.MediaKind;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.SourceApp;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.internal.MiniJson;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.Device;
import lol.trq.orpheus.source.Devices;
import lol.trq.orpheus.source.LibraryAccess;
import lol.trq.orpheus.source.Playlist;
import lol.trq.orpheus.source.QueueAccess;
import lol.trq.orpheus.source.QueueItem;
import lol.trq.orpheus.source.Reply;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.source.Transport;

/**
 * What a Spotify account is playing, on any device, through the Spotify Web API. The only part of the library that
 * uses the network, and only with a token you supply.
 *
 * <p>Reading needs the scope {@code user-read-playback-state}, controls {@code user-modify-playback-state} and
 * Premium. Put this source before the system sessions to prefer it; they take over while it has nothing to show.
 */
@Experimental
public final class SpotifySource implements Source, Transport, QueueAccess, Devices, LibraryAccess {
    public static final String ID = "spotify";

    private static final String API = "https://api.spotify.com/v1";
    private static final SourceApp APP = new SourceApp("spotify", "Spotify");
    private static final Pattern URI_FORM =
            Pattern.compile("spotify:(track|episode|album|artist|playlist|show):[A-Za-z0-9]{1,64}");
    private static final Pattern DEVICE_FORM = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final int MAX_JSON = 4 << 20;
    private static final int MAX_COVER = 8 << 20;

    private static final long PLAYING_MILLIS = 5_000;
    private static final long PAUSED_MILLIS = 10_000;
    private static final long NOTHING_MILLIS = 15_000;
    private static final long AFTER_COMMAND_MILLIS = 400;
    private static final long AFTER_END_MILLIS = 700;
    private static final long HINT_GAP_MILLIS = 1_500;
    private static final long NO_TOKEN_MILLIS = 60_000;
    private static final long FORBIDDEN_MILLIS = 300_000;
    private static final long QUOTA_MILLIS = 300_000;
    private static final long MAX_WAIT_MILLIS = 24L * 3_600_000;
    private static final int FAILURES_BEFORE_UNAVAILABLE = 3;

    private final TokenSource tokens;
    private final HttpCall http;

    private volatile Run run;
    private volatile String link;

    private SpotifySource(TokenSource tokens, HttpCall http) {
        this.tokens = tokens;
        this.http = http;
    }

    public static SpotifySource create(TokenSource tokens) {
        return create(tokens, new UrlConnectionCall());
    }

    public static SpotifySource create(TokenSource tokens, HttpCall http) {
        return new SpotifySource(tokens, http);
    }

    @Override
    public String id() {
        return ID;
    }

    /** Artwork may not be altered and the data must be shown with Spotify's name and a link to the track. */
    @Override
    public DisplayRules display() {
        return new DisplayRules(true, "Spotify", link);
    }

    @Override
    public synchronized void start(SourceSink sink) {
        if (run == null) {
            run = new Run(sink);
            sink.status(SourceStatus.STARTING);
            run.schedule(0);
        }
    }

    @Override
    public void stop() {
        Run ending;
        synchronized (this) {
            ending = run;
            run = null;
        }
        if (ending != null) {
            ending.end();
        }
        link = null;
    }

    /** Asks for the state now, for example after the user signed in. Does not shorten a rate limit. */
    public void refresh() {
        Run current = run;
        if (current != null) {
            current.hint();
        }
    }

    /** Another source saw its Spotify session change, which is sooner than the next poll would. */
    @Override
    public void changedElsewhere(SourceApp app) {
        if (app.id().toLowerCase(Locale.ROOT).contains("spotify")) {
            refresh();
        }
    }

    @Override
    public CompletableFuture<ControlResult> send(ControlRequest request) {
        String player = API + "/me/player";
        return switch (request.control()) {
            case PLAY -> command("PUT", player + "/play", null);
            case PAUSE -> command("PUT", player + "/pause", null);
            case NEXT -> command("POST", player + "/next", null);
            case PREVIOUS -> command("POST", player + "/previous", null);
            case SEEK -> command("PUT", player + "/seek?position_ms=" + Math.max(0, request.positionMillis()), null);
            case SHUFFLE -> command("PUT", player + "/shuffle?state=" + request.on(), null);
            case REPEAT -> command("PUT", player + "/repeat?state=" + repeat(request.repeat()), null);
            case VOLUME -> {
                long percent = Math.round(Math.max(0, Math.min(1, request.value())) * 100);
                yield command("PUT", player + "/volume?volume_percent=" + percent, null);
            }
            default -> CompletableFuture.completedFuture(ControlResult.unsupported("not offered by Spotify"));
        };
    }

    @Override
    public CompletableFuture<Reply<List<QueueItem>>> queue(String sessionId) {
        return ask("/me/player/queue", List.of(), root -> {
            List<QueueItem> items = new ArrayList<>();
            for (Object item : list(root.get("queue"))) {
                Track track = track(map(item));
                if (!track.isEmpty()) {
                    items.add(new QueueItem(items.size() + ":" + track.uri(), track));
                }
            }
            return List.copyOf(items);
        });
    }

    @Override
    public CompletableFuture<ControlResult> move(String sessionId, String itemId, String afterItemId) {
        return CompletableFuture.completedFuture(ControlResult.unsupported("Spotify cannot reorder the queue"));
    }

    @Override
    public CompletableFuture<ControlResult> remove(String sessionId, String itemId) {
        return CompletableFuture.completedFuture(ControlResult.unsupported("Spotify cannot remove from the queue"));
    }

    @Override
    public CompletableFuture<ControlResult> add(String sessionId, String uri) {
        if (!valid(uri)) {
            return badUri();
        }
        return command("POST", API + "/me/player/queue?uri=" + encode(uri), null);
    }

    @Override
    public CompletableFuture<Reply<List<Device>>> devices(String sessionId) {
        return ask("/me/player/devices", List.of(), root -> {
            List<Device> devices = new ArrayList<>();
            for (Object item : list(root.get("devices"))) {
                Map<?, ?> device = map(item);
                String id = text(device.get("id"));
                if (!id.isEmpty()) {
                    devices.add(new Device(
                            id,
                            text(device.get("name")),
                            text(device.get("type")).toLowerCase(Locale.ROOT),
                            Boolean.TRUE.equals(device.get("is_active"))));
                }
            }
            return List.copyOf(devices);
        });
    }

    @Override
    public CompletableFuture<ControlResult> transfer(String sessionId, String deviceId) {
        if (deviceId == null || !DEVICE_FORM.matcher(deviceId).matches()) {
            return CompletableFuture.completedFuture(ControlResult.refused("not a device id"));
        }
        return command("PUT", API + "/me/player", "{\"device_ids\":[\"" + deviceId + "\"]}");
    }

    /** Needs the scope {@code user-library-modify}. */
    @Override
    public CompletableFuture<ControlResult> like(String sessionId, boolean liked) {
        Run current = run;
        String uri = current == null ? null : current.trackUri;
        if (!valid(uri)) {
            return CompletableFuture.completedFuture(ControlResult.refused("nothing that can be saved is playing"));
        }
        return command(liked ? "PUT" : "DELETE", API + "/me/library?uris=" + encode(uri), null);
    }

    /** The first 50 playlists. Needs the scope {@code playlist-read-private}. */
    @Override
    public CompletableFuture<Reply<List<Playlist>>> playlists() {
        return ask("/me/playlists?limit=50", List.of(), root -> {
            List<Playlist> playlists = new ArrayList<>();
            for (Object item : list(root.get("items"))) {
                Map<?, ?> playlist = map(item);
                if (valid(text(playlist.get("uri")))) {
                    Map<?, ?> size =
                            map(playlist.get("items") instanceof Map ? playlist.get("items") : playlist.get("tracks"));
                    playlists.add(new Playlist(text(playlist.get("uri")), text(playlist.get("name")), (int)
                            number(size.get("total"), -1)));
                }
            }
            return List.copyOf(playlists);
        });
    }

    /** At most 10 results. */
    @Override
    public CompletableFuture<Reply<List<Track>>> search(String query, int limit) {
        int count = Math.max(1, Math.min(10, limit));
        return ask(
                "/search?type=track&limit=" + count + "&q=" + encode(query == null ? "" : query), List.of(), root -> {
                    List<Track> tracks = new ArrayList<>();
                    for (Object item : list(map(root.get("tracks")).get("items"))) {
                        Track track = track(map(item));
                        if (!track.isEmpty()) {
                            tracks.add(track);
                        }
                    }
                    return List.copyOf(tracks);
                });
    }

    @Override
    public CompletableFuture<ControlResult> play(String sessionId, String uri) {
        if (!valid(uri)) {
            return badUri();
        }
        boolean single = uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:");
        return command(
                "PUT",
                API + "/me/player/play",
                single ? "{\"uris\":[\"" + uri + "\"]}" : "{\"context_uri\":\"" + uri + "\"}");
    }

    private CompletableFuture<ControlResult> command(String method, String url, String body) {
        Run current = run;
        if (current == null) {
            return CompletableFuture.completedFuture(ControlResult.failed("stopped"));
        }
        return current.onCommands(
                () -> {
                    HttpCall.Answer answer = current.call(method, url, body);
                    ControlResult result = outcome(answer);
                    if (result.done()) {
                        current.schedule(AFTER_COMMAND_MILLIS);
                    }
                    return result;
                },
                reason -> ControlResult.failed(reason));
    }

    private <T> CompletableFuture<Reply<T>> ask(String path, T empty, Function<Map<?, ?>, T> read) {
        Run current = run;
        if (current == null) {
            return CompletableFuture.completedFuture(new Reply<>(ControlResult.failed("stopped"), empty));
        }
        return current.onCommands(
                () -> {
                    HttpCall.Answer answer = current.call("GET", API + path, null);
                    ControlResult result = outcome(answer);
                    return result.done() ? Reply.of(read.apply(map(json(answer.body())))) : new Reply<>(result, empty);
                },
                reason -> new Reply<>(ControlResult.failed(reason), empty));
    }

    private static CompletableFuture<ControlResult> badUri() {
        return CompletableFuture.completedFuture(ControlResult.refused("not a Spotify uri"));
    }

    static ControlResult outcome(HttpCall.Answer answer) {
        int status = answer.status();
        if (status >= 200 && status < 300) {
            return ControlResult.DONE;
        }
        String reason = reason(answer);
        if (status == 403) {
            return reason.equals("PREMIUM_REQUIRED")
                    ? ControlResult.refused("needs Spotify Premium")
                    : ControlResult.refused(reason.isEmpty() ? "not allowed" : reason);
        }
        if (status == 404) {
            return ControlResult.refused(reason.isEmpty() ? "not found" : reason);
        }
        if (status == 429) {
            return ControlResult.refused("rate limited");
        }
        return ControlResult.failed("HTTP " + status);
    }

    /** The machine-readable reason of an error answer, or an empty string. */
    static String reason(HttpCall.Answer answer) {
        try {
            Map<?, ?> root = map(json(answer.body()));
            Object nested = map(root.get("error")).get("reason");
            String reason = text(nested == null ? root.get("reason") : nested);
            return reason.matches("[A-Z_]{1,64}") ? reason : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Reads the answer of the player endpoint, or null when nothing is active. */
    static SessionUpdate session(Map<?, ?> root, long measuredNanos, CoverData cover) {
        Map<?, ?> item = map(root.get("item"));
        Map<?, ?> device = map(root.get("device"));
        if (item.isEmpty() && !Boolean.TRUE.equals(root.get("is_playing"))) {
            return null;
        }
        boolean playing = Boolean.TRUE.equals(root.get("is_playing"));
        double volume = number(device.get("volume_percent"), -1);
        return new SessionUpdate(
                ID,
                APP,
                track(item),
                playing ? PlaybackState.PLAYING : PlaybackState.PAUSED,
                (long) number(root.get("progress_ms"), 0),
                measuredNanos,
                1,
                root.get("shuffle_state") instanceof Boolean on ? (on ? Shuffle.ON : Shuffle.OFF) : Shuffle.UNKNOWN,
                switch (text(root.get("repeat_state"))) {
                    case "off" -> Repeat.OFF;
                    case "track" -> Repeat.ONE;
                    case "context" -> Repeat.ALL;
                    default -> Repeat.UNKNOWN;
                },
                volume < 0 ? -1 : volume / 100,
                controls(map(map(root.get("actions")).get("disallows")), device, playing),
                cover,
                imageUrl(item));
    }

    private static Set<Control> controls(Map<?, ?> disallows, Map<?, ?> device, boolean playing) {
        Set<Control> controls = EnumSet.noneOf(Control.class);
        if (Boolean.TRUE.equals(device.get("is_restricted"))) {
            return controls;
        }
        controls.addAll(List.of(
                Control.PLAY,
                Control.PAUSE,
                Control.NEXT,
                Control.PREVIOUS,
                Control.SEEK,
                Control.SHUFFLE,
                Control.REPEAT));
        if (!Boolean.FALSE.equals(device.get("supports_volume"))) {
            controls.add(Control.VOLUME);
        }
        // pausing is also disallowed while paused, and resuming while playing: that is no reason to hide them
        if (playing && blocked(disallows, "pausing")) {
            controls.remove(Control.PAUSE);
        }
        if (!playing && blocked(disallows, "resuming")) {
            controls.remove(Control.PLAY);
        }
        if (blocked(disallows, "skipping_next")) {
            controls.remove(Control.NEXT);
        }
        if (blocked(disallows, "skipping_prev")) {
            controls.remove(Control.PREVIOUS);
        }
        if (blocked(disallows, "seeking")) {
            controls.remove(Control.SEEK);
        }
        if (blocked(disallows, "toggling_shuffle")) {
            controls.remove(Control.SHUFFLE);
        }
        if (blocked(disallows, "toggling_repeat_context") && blocked(disallows, "toggling_repeat_track")) {
            controls.remove(Control.REPEAT);
        }
        return controls;
    }

    private static boolean blocked(Map<?, ?> disallows, String action) {
        return Boolean.TRUE.equals(disallows.get(action));
    }

    private static String repeat(Repeat repeat) {
        return switch (repeat) {
            case ONE -> "track";
            case ALL -> "context";
            default -> "off";
        };
    }

    /** A track, an episode or a local file. Ads and restricted items arrive as null. */
    static Track track(Map<?, ?> item) {
        if (item.isEmpty()) {
            return Track.NONE;
        }
        Map<?, ?> album = map(item.get("album"));
        List<String> artists = names(list(item.get("artists")));
        Map<?, ?> show = map(item.get("show"));
        if (artists.isEmpty() && !show.isEmpty()) {
            artists = List.of(text(show.get("name")));
        }
        String id = text(item.get("id"));
        return new Track(
                id.isEmpty() ? null : id,
                text(item.get("name")),
                artists,
                text(show.isEmpty() ? album.get("name") : show.get("name")),
                String.join(", ", names(list(album.get("artists")))),
                (long) number(item.get("duration_ms"), -1),
                (int) number(item.get("track_number"), 0),
                text(item.get("uri")),
                text(item.get("type")).equals("episode") ? MediaKind.OTHER : MediaKind.MUSIC);
    }

    private static List<String> names(Collection<?> people) {
        List<String> names = new ArrayList<>();
        for (Object person : people) {
            names.add(text(map(person).get("name")));
        }
        return names;
    }

    /** The smallest image that is at least 300 pixels wide, or the largest there is. Null when none is on a CDN. */
    static String imageUrl(Map<?, ?> item) {
        Object images = map(item.get("album")).get("images");
        if (images == null) {
            images = item.get("images");
        }
        String best = null;
        double bestWidth = -1;
        for (Object entry : list(images)) {
            Map<?, ?> image = map(entry);
            String url = text(image.get("url"));
            double width = number(image.get("width"), 0);
            boolean better = best == null || (bestWidth < 300 ? width > bestWidth : width >= 300 && width < bestWidth);
            if (better && cdn(url)) {
                best = url;
                bestWidth = width;
            }
        }
        return best;
    }

    static boolean cdn(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && host != null
                    && (host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean valid(String uri) {
        return uri != null && URI_FORM.matcher(uri).matches();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Object json(byte[] body) throws IOException {
        try {
            return body.length == 0 ? Map.of() : MiniJson.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException("the answer is not JSON");
        }
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private static double number(Object value, double missing) {
        return value instanceof Number number ? number.doubleValue() : missing;
    }

    private interface Work<T> {
        T run() throws IOException, InterruptedException;
    }

    private static final class NoToken extends IOException {
        private static final long serialVersionUID = 1;

        NoToken() {
            super("not signed in");
        }
    }

    private static final class Limited extends IOException {
        private static final long serialVersionUID = 1;

        Limited() {
            super("rate limited");
        }
    }

    /** One start-to-stop cycle. Polling and commands have a thread each, so a slow poll does not delay a command. */
    private final class Run {
        final SourceSink sink;
        final ScheduledThreadPoolExecutor polling = executor("orpheus-spotify");
        final ScheduledThreadPoolExecutor commands = executor("orpheus-spotify-commands");
        final Set<CompletableFuture<?>> open = ConcurrentHashMap.newKeySet();
        volatile boolean ended;
        volatile String trackUri;
        private volatile long limitedUntil = System.nanoTime();
        private volatile long lastPoll = System.nanoTime() - TimeUnit.HOURS.toNanos(1);
        private ScheduledFuture<?> next;
        private long nextAt;
        private int failures;
        private SourceStatus status;
        private CoverData cover;
        private String coverUrl;
        private String failedCoverUrl;

        Run(SourceSink sink) {
            this.sink = sink;
        }

        private ScheduledThreadPoolExecutor executor(String name) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, name);
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            return executor;
        }

        void end() {
            ended = true;
            polling.shutdownNow();
            commands.shutdownNow();
            for (CompletableFuture<?> future : open) {
                future.cancel(false);
            }
            try {
                polling.awaitTermination(4, TimeUnit.SECONDS);
                commands.awaitTermination(4, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Polls after the delay, or sooner when a poll is already due, but never inside a rate limit. */
        synchronized void schedule(long millis) {
            if (ended) {
                return;
            }
            long now = System.nanoTime();
            long at = now + TimeUnit.MILLISECONDS.toNanos(millis);
            if (at - limitedUntil < 0) {
                at = limitedUntil;
            }
            if (next != null && !next.isDone() && nextAt - at <= 0) {
                return;
            }
            if (next != null) {
                next.cancel(false);
            }
            nextAt = at;
            try {
                next = polling.schedule(this::poll, Math.max(0, at - now), TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException ignored) {
                // stopping
            }
        }

        void hint() {
            long since = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastPoll);
            schedule(Math.max(150, HINT_GAP_MILLIS - since));
        }

        <T> CompletableFuture<T> onCommands(Work<T> work, Function<String, T> failed) {
            CompletableFuture<T> future = new CompletableFuture<>();
            open.add(future);
            future.whenComplete((value, error) -> open.remove(future));
            try {
                commands.execute(() -> {
                    try {
                        future.complete(future.isDone() ? null : work.run());
                    } catch (IOException | InterruptedException | RuntimeException e) {
                        future.complete(failed.apply(String.valueOf(e.getMessage())));
                    }
                });
            } catch (RejectedExecutionException e) {
                future.complete(failed.apply("stopped"));
            }
            return future;
        }

        /** Calls the API with a token. A rejected token is replaced once. */
        HttpCall.Answer call(String method, String url, String body) throws IOException, InterruptedException {
            if (System.nanoTime() - limitedUntil < 0) {
                throw new Limited();
            }
            String token = token();
            HttpCall.Answer answer = authorized(method, url, body, token);
            if (answer.status() == 401) {
                tokens.rejected(token);
                String fresh = token();
                if (fresh.equals(token)) {
                    throw new NoToken();
                }
                answer = authorized(method, url, body, fresh);
                if (answer.status() == 401) {
                    tokens.rejected(fresh);
                    throw new NoToken();
                }
            }
            if (answer.status() == 429) {
                long wait = answer.retryAfterSeconds() >= 0
                        ? Math.max(1_000, answer.retryAfterSeconds() * 1000)
                        : reason(answer).equals("QUOTA_EXCEEDED") ? QUOTA_MILLIS : 30_000;
                limitedUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(MAX_WAIT_MILLIS, wait));
            }
            return answer;
        }

        private HttpCall.Answer authorized(String method, String url, String body, String token) throws IOException {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + token);
            headers.put("Accept", "application/json");
            if (body != null) {
                headers.put("Content-Type", "application/json");
            }
            return http.send(
                    method, url, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8), MAX_JSON);
        }

        private String token() throws IOException, InterruptedException {
            try {
                CompletableFuture<String> future = tokens.accessToken();
                String token = future == null ? null : future.get(10, TimeUnit.SECONDS);
                if (token == null || token.isBlank() || token.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
                    throw new NoToken();
                }
                return token;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                throw new IOException("no token could be obtained");
            }
        }

        private void poll() {
            synchronized (this) {
                if (ended) {
                    return;
                }
                next = null;
            }
            lastPoll = System.nanoTime();
            long delay;
            try {
                delay = read();
            } catch (NoToken e) {
                delay = unavailable(e.getMessage(), NO_TOKEN_MILLIS);
            } catch (Limited e) {
                delay = limited();
            } catch (InterruptedException e) {
                return;
            } catch (IOException | RuntimeException e) {
                delay = failed(String.valueOf(e.getMessage()));
            }
            schedule(delay);
        }

        private long read() throws IOException, InterruptedException {
            long before = System.nanoTime();
            HttpCall.Answer answer = call("GET", API + "/me/player?additional_types=track,episode", null);
            long measured = before + (System.nanoTime() - before) / 2;
            if (answer.status() == 429) {
                return limited();
            }
            if (answer.status() == 403) {
                String reason = reason(answer);
                return unavailable(
                        reason.isEmpty() ? "this account is not allowed to use the app" : reason, FORBIDDEN_MILLIS);
            }
            if (answer.status() != 200 && answer.status() != 204) {
                return failed("HTTP " + answer.status());
            }
            failures = 0;
            Map<?, ?> root = map(json(answer.body()));
            SessionUpdate session = session(root, measured, cover);
            if (session == null) {
                publish(null);
                report(SourceStatus.READY);
                return NOTHING_MILLIS;
            }
            String image = session.coverUri();
            if (image == null || !image.equals(coverUrl)) {
                cover = null;
                coverUrl = null;
                session = session(root, measured, null);
            }
            publish(session);
            report(SourceStatus.READY);
            if (image != null && cover == null && !image.equals(failedCoverUrl) && fetchCover(image)) {
                publish(session(root, measured, cover));
            }
            if (session.state() != PlaybackState.PLAYING) {
                return PAUSED_MILLIS;
            }
            long left = session.track().durationMillis() - session.positionMillis();
            return left > 0 ? Math.min(PLAYING_MILLIS, left + AFTER_END_MILLIS) : PLAYING_MILLIS;
        }

        private boolean fetchCover(String url) {
            try {
                HttpCall.Answer answer = http.send("GET", url, Map.of(), null, MAX_COVER);
                if (answer.status() == 200 && answer.body().length > 0) {
                    cover = new CoverData(url, answer.body());
                    coverUrl = url;
                    return true;
                }
                // a missing image stays missing; anything else is tried again with the next poll
                if (answer.status() >= 400 && answer.status() < 500 && answer.status() != 429) {
                    failedCoverUrl = url;
                }
            } catch (IOException | RuntimeException e) {
                failedCoverUrl = null;
            }
            return false;
        }

        private void publish(SessionUpdate session) {
            if (ended) {
                return;
            }
            String uri = session == null ? null : session.track().uri();
            trackUri = uri;
            link = session == null || session.track().id() == null || !valid(uri)
                    ? null
                    : "https://open.spotify.com/"
                            + uri.substring("spotify:".length()).replace(':', '/');
            sink.sessions(session == null ? List.of() : List.of(session));
        }

        private void report(SourceStatus now) {
            if (!ended && !now.equals(status)) {
                status = now;
                sink.status(now);
            }
        }

        private long unavailable(String reason, long millis) {
            failures = 0;
            publish(null);
            report(SourceStatus.unavailable(reason));
            return millis;
        }

        private long limited() {
            long millis = Math.max(0, TimeUnit.NANOSECONDS.toMillis(limitedUntil - System.nanoTime()));
            if (millis > 2 * PAUSED_MILLIS) {
                return unavailable("rate limited", millis);
            }
            return millis;
        }

        private long failed(String reason) {
            failures++;
            if (failures >= FAILURES_BEFORE_UNAVAILABLE) {
                publish(null);
                report(SourceStatus.unavailable(reason));
            }
            return Math.min(60_000, 2_000L << Math.min(failures, 5));
        }
    }
}
