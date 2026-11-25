/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.macos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.Experimental;
import lol.trq.orpheus.MediaKind;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.SourceApp;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.cover.CoverDecoder;
import lol.trq.orpheus.internal.Backoff;
import lol.trq.orpheus.internal.MiniJson;
import lol.trq.orpheus.internal.NativeFiles;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.source.Transport;

/**
 * What macOS shows as "Now Playing". Only the one application the system has elected is visible.
 *
 * <p>macOS has no public interface for this. The data comes from a helper, mediaremote-adapter (BSD-3-Clause,
 * bundled in the jar), which this source runs as a child process through the system's {@code /usr/bin/perl}. It
 * rests on a private part of macOS, so any system update can stop it working; the source then reports itself
 * unavailable and nothing else happens. Commands go to the elected application and are sent by short-lived child
 * processes.
 */
@Experimental
public final class MacNowPlaying implements Source, Transport {
    private static final String PERL = "/usr/bin/perl";
    private static final String RESOURCES = "orpheus-native/1/macos-universal";
    private static final String SCRIPT = "mediaremote-adapter.pl";
    private static final String FRAMEWORK = "MediaRemoteAdapter.framework";
    private static final int MAX_LINE_CHARS = 48 << 20;
    private static final int MAX_FAILURES = 5;
    private static final int MAX_NAME_ATTEMPTS = 5;
    private static final long NAME_RETRY_NANOS = 2_000_000_000L;
    private static final long HEALTHY_NANOS = 30_000_000_000L;
    private static final int KEPT_LINE_CHARS = 64 << 10;
    /** Key under which the process name is kept in the held state; never sent by the helper. */
    static final String PROCESS_NAME = "orpheus.processName";

    private static final long MICROS_PER_DAY = 86_400_000_000L;

    private final Function<List<String>, List<String>> commandLine;
    private final Path adapterDirectory;
    private final Object lifecycle = new Object();
    private Thread reader;
    private ExecutorService commands;
    private Thread commandThread;
    private Process stream;
    private Thread hook;
    private boolean stopping;
    private volatile String sessionId = "";
    private long namedPid;
    private SessionUpdate held;
    private int nameAttempts;
    private long nextNameAt;
    private String processName = "";
    private volatile List<String> prefix;
    long commandTimeoutMillis = 3000;

    private MacNowPlaying(Path adapterDirectory, Function<List<String>, List<String>> commandLine) {
        this.adapterDirectory = adapterDirectory;
        this.commandLine = commandLine;
    }

    public static MacNowPlaying create() {
        return new MacNowPlaying(null, null);
    }

    /**
     * Uses a helper you built yourself instead of the bundled one.
     *
     * @param adapterDirectory a directory holding {@code mediaremote-adapter.pl} and
     *     {@code MediaRemoteAdapter.framework}
     */
    public static MacNowPlaying create(Path adapterDirectory) {
        return new MacNowPlaying(adapterDirectory, null);
    }

    /** For tests: {@code commandLine} turns helper arguments into the full command to run. */
    static MacNowPlaying withCommand(Function<List<String>, List<String>> commandLine) {
        return new MacNowPlaying(null, commandLine);
    }

    @Override
    public String id() {
        return "macos";
    }

    @Override
    public boolean foreign() {
        return true;
    }

    @Override
    public void start(SourceSink sink) {
        synchronized (lifecycle) {
            if (reader != null) {
                return;
            }
            stopping = false;
            commands = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "orpheus-macos-commands");
                thread.setDaemon(true);
                commandThread = thread;
                return thread;
            });
            hook = new Thread(
                    () -> {
                        synchronized (lifecycle) {
                            stopping = true;
                            lifecycle.notifyAll();
                            destroyStream();
                        }
                    },
                    "orpheus-macos-shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(hook);
            } catch (IllegalStateException e) {
                // the JVM is ending: a helper started now would be left behind
                hook = null;
                commands.shutdownNow();
                commands = null;
                sink.status(SourceStatus.unavailable("the JVM is shutting down"));
                return;
            }
            reader = new Thread(() -> run(sink), "orpheus-macos");
            reader.setDaemon(true);
            reader.start();
        }
    }

    @Override
    public void stop() {
        Thread ending;
        Thread endingCommandThread;
        ExecutorService endingCommands;
        synchronized (lifecycle) {
            ending = reader;
            endingCommands = commands;
            endingCommandThread = commandThread;
            reader = null;
            commands = null;
            commandThread = null;
            stopping = true;
            destroyStream();
            if (hook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // the JVM is already shutting down
                }
                hook = null;
            }
            lifecycle.notifyAll();
        }
        if (endingCommands != null) {
            endingCommands.shutdownNow();
        }
        try {
            if (ending != null && ending != Thread.currentThread()) {
                ending.join(10_000);
            }
            if (endingCommandThread != null && endingCommandThread != Thread.currentThread()) {
                endingCommandThread.join(5000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void destroyStream() {
        synchronized (lifecycle) {
            if (stream != null) {
                stream.destroy();
                try {
                    if (!stream.waitFor(1, TimeUnit.SECONDS)) {
                        stream.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    stream.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
                stream = null;
            }
        }
    }

    /** Works out how to launch the helper. Returns the reason when it cannot be launched here. */
    private String prepare() {
        if (commandLine != null) {
            prefix = List.of();
            return null;
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")) {
            return "not running on macOS";
        }
        if (!Files.isExecutable(Path.of(PERL))) {
            return PERL + " is missing, and the helper needs it";
        }
        try {
            Path directory = adapterDirectory != null
                    ? adapterDirectory
                    : NativeFiles.extract(MacNowPlaying.class, RESOURCES, "a", NativeFiles.cacheRoot());
            Path script = directory.resolve(SCRIPT);
            Path framework = directory.resolve(FRAMEWORK);
            if (!Files.isRegularFile(script) || !Files.isDirectory(framework)) {
                return "the helper is incomplete in " + directory;
            }
            prefix = List.of(PERL, script.toString(), framework.toString());
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private List<String> command(String... arguments) {
        List<String> helper = List.of(arguments);
        if (commandLine != null) {
            return commandLine.apply(helper);
        }
        List<String> full = new ArrayList<>(prefix);
        full.addAll(helper);
        return full;
    }

    private void run(SourceSink sink) {
        sink.status(SourceStatus.STARTING);
        String failure = prepare();
        if (failure != null) {
            sink.status(SourceStatus.unavailable(failure));
            return;
        }
        Backoff backoff = new Backoff();
        int failures = 0;
        while (true) {
            Process process;
            StringBuilder errors = new StringBuilder();
            synchronized (lifecycle) {
                if (stopping) {
                    return;
                }
                try {
                    process = new ProcessBuilder(command("stream", "--micros")).start();
                } catch (IOException e) {
                    sink.status(SourceStatus.unavailable("the helper could not be started: " + e.getMessage()));
                    if (++failures >= MAX_FAILURES) {
                        return;
                    }
                    try {
                        lifecycle.wait(backoff.nextMillis());
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    continue;
                }
                stream = process;
            }
            Thread drain = new Thread(() -> drain(process.getErrorStream(), errors), "orpheus-macos-errors");
            drain.setDaemon(true);
            drain.start();
            long startedAt = System.nanoTime();
            namedPid = 0;
            processName = "";
            boolean worked = read(process, sink) && System.nanoTime() - startedAt > HEALTHY_NANOS;
            int exit = -1;
            try {
                process.waitFor(2, TimeUnit.SECONDS);
                exit = process.isAlive() ? -1 : process.exitValue();
                drain.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
            sessionId = "";
            sink.sessions(List.of());
            synchronized (lifecycle) {
                if (stopping) {
                    return;
                }
                stream = null;
                failures = worked ? 0 : failures + 1;
                if (worked) {
                    backoff.reset();
                }
                String reason;
                synchronized (errors) {
                    reason = errors.length() == 0 ? "the helper exited with code " + exit : errors.toString();
                }
                sink.status(SourceStatus.unavailable(reason));
                if (failures >= MAX_FAILURES) {
                    return;
                }
                try {
                    lifecycle.wait(backoff.nextMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void drain(InputStream stream, StringBuilder errors) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                synchronized (errors) {
                    if (errors.length() < 400 && !line.isBlank()) {
                        errors.append(errors.length() == 0 ? "" : "; ").append(line.strip());
                    }
                }
            }
        } catch (IOException e) {
            // the process ended
        }
    }

    /** Reads lines until the process ends. Returns whether it produced at least one usable line. */
    private boolean read(Process process, SourceSink sink) {
        boolean worked = false;
        Map<String, Object> state = new HashMap<>();
        CoverCache cover = new CoverCache();
        try (InputStreamReader in = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            boolean overflow = false;
            char[] buffer = new char[8192];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                for (int i = 0; i < n; i++) {
                    char c = buffer[i];
                    if (c != '\n') {
                        if (line.length() < MAX_LINE_CHARS) {
                            line.append(c);
                        } else {
                            overflow = true;
                        }
                        continue;
                    }
                    if (!overflow && apply(line.toString(), state)) {
                        if (!worked) {
                            worked = true;
                            sink.status(SourceStatus.READY);
                        }
                        sink.sessions(sessions(state, cover));
                    }
                    if (line.capacity() > KEPT_LINE_CHARS) {
                        line = new StringBuilder();
                    }
                    line.setLength(0);
                    overflow = false;
                }
            }
        } catch (IOException e) {
            // the process ended
        }
        return worked;
    }

    /** Merges one line into the held state. Returns false for lines that are not understood. */
    static boolean apply(String line, Map<String, Object> state) {
        Object parsed;
        try {
            parsed = MiniJson.parse(line);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!(parsed instanceof Map<?, ?> message) || !(message.get("payload") instanceof Map<?, ?> payload)) {
            return false;
        }
        if (!Boolean.TRUE.equals(message.get("diff"))) {
            state.clear();
        }
        for (Map.Entry<?, ?> entry : payload.entrySet()) {
            if (entry.getValue() == null) {
                state.remove(String.valueOf(entry.getKey()));
            } else {
                state.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return true;
    }

    /** Remembers the decoded artwork so the same picture keeps the same {@link CoverData} instance. */
    static final class CoverCache {
        private String encoded;
        private CoverData data;

        CoverData of(Object artwork) {
            if (!(artwork instanceof String text) || text.isEmpty()) {
                encoded = null;
                data = null;
                return null;
            }
            if (!text.equals(encoded)) {
                encoded = text;
                data = null;
                if (text.length() <= CoverDecoder.MAX_ENCODED_BYTES * 4L / 3 + 4) {
                    try {
                        data = CoverData.of(Base64.getMimeDecoder().decode(text));
                    } catch (IllegalArgumentException e) {
                        // not base64: no cover
                    }
                }
            }
            return data;
        }
    }

    private List<SessionUpdate> sessions(Map<String, Object> state, CoverCache cover) {
        if (state.isEmpty()) {
            namedPid = 0;
            processName = "";
        } else if (appOf(state).isEmpty()) {
            long pid = micros(state.get("processIdentifier"));
            if (pid != namedPid) {
                namedPid = pid;
                processName = "";
                nameAttempts = 0;
                nextNameAt = System.nanoTime();
            }
            long now = System.nanoTime();
            if (processName.isEmpty() && pid > 0 && nameAttempts < MAX_NAME_ATTEMPTS && now - nextNameAt >= 0) {
                nameAttempts++;
                nextNameAt = now + NAME_RETRY_NANOS * nameAttempts;
                processName = processName(pid);
            }
            if (processName.isEmpty()) {
                state.remove(PROCESS_NAME);
            } else {
                state.put(PROCESS_NAME, processName);
            }
        }
        SessionUpdate update = steady(held, session(state, cover, System.nanoTime(), System.currentTimeMillis()));
        held = update;
        sessionId = update == null ? "" : update.sessionId();
        return update == null ? List.of() : List.of(update);
    }

    // macOS holds back a nowPlayingInfo set that follows another within about 400 ms by about 530 ms, then stamps it
    // with its arrival time and the elapsed time of the set.
    /** Least shortfall against the held anchor that is taken for a held-back copy. */
    static final long LATE_MIN_MILLIS = 490;
    /** Largest shortfall against the held anchor that is taken for a held-back copy. */
    static final long LATE_MAX_MILLIS = 630;
    /** Least time from the held anchor to a held-back copy. */
    static final long LATE_MIN_GAP_NANOS = 500_000_000L;
    /** Largest time from the held anchor to a held-back copy. */
    static final long LATE_MAX_GAP_NANOS = 1_050_000_000L;

    /** Keeps the held anchor when the next update is a held-back copy of the same position. */
    static SessionUpdate steady(SessionUpdate held, SessionUpdate next) {
        if (held == null
                || next == null
                || !held.sessionId().equals(next.sessionId())
                || !held.track().equals(next.track())
                || held.state() != PlaybackState.PLAYING
                || next.state() != PlaybackState.PLAYING
                || held.rate() != next.rate()) {
            return next;
        }
        long gap = next.measuredNanos() - held.measuredNanos();
        if (gap < LATE_MIN_GAP_NANOS || gap > LATE_MAX_GAP_NANOS) {
            return next;
        }
        double behind = held.positionMillis() + gap / 1e6 * held.rate() - next.positionMillis();
        if (behind < LATE_MIN_MILLIS || behind > LATE_MAX_MILLIS) {
            return next;
        }
        return new SessionUpdate(
                next.sessionId(),
                next.app(),
                next.track(),
                next.state(),
                held.positionMillis(),
                held.measuredNanos(),
                next.rate(),
                next.shuffle(),
                next.repeat(),
                next.volume(),
                next.controls(),
                next.cover(),
                next.coverUri());
    }

    static SessionUpdate session(Map<String, Object> state, CoverCache cover, long nanoTime, long wallMillis) {
        if (state.isEmpty()) {
            return null;
        }
        String app = appOf(state);
        if (app.isEmpty()) {
            app = text(state.get(PROCESS_NAME));
        }
        if (app.isEmpty()) {
            app = "unknown";
        }
        boolean playing = Boolean.TRUE.equals(state.get("playing"));
        long durationMicros = micros(state.get("durationMicros"));
        long elapsedMicros = Math.max(0, micros(state.get("elapsedTimeMicros")));
        long stampMicros = micros(state.get("timestampEpochMicros"));
        long ageMicros = stampMicros <= 0 ? 0 : wallMillis * 1000 - stampMicros;
        if (ageMicros < 0 || ageMicros > MICROS_PER_DAY) {
            ageMicros = 0;
        }
        double rate = state.get("playbackRate") instanceof Double r && r > 0 ? r : 1;
        String kind = text(state.get("mediaType")).toLowerCase(Locale.ROOT);
        MediaKind media = kind.contains("video")
                ? MediaKind.VIDEO
                : kind.contains("audio") || Boolean.TRUE.equals(state.get("isMusicApp"))
                        ? MediaKind.MUSIC
                        : MediaKind.UNKNOWN;
        Set<Control> controls = controlsOf(state);
        String artist = text(state.get("artist"));
        String identifier = text(state.get("uniqueIdentifier"));
        return new SessionUpdate(
                app,
                new SourceApp(app, app),
                new Track(
                        identifier.isEmpty() ? null : identifier,
                        text(state.get("title")),
                        artist.isEmpty() ? List.of() : List.of(artist),
                        text(state.get("album")),
                        "",
                        durationMicros > 0 ? durationMicros / 1000 : -1,
                        state.get("trackNumber") instanceof Double n ? n.intValue() : 0,
                        null,
                        media),
                playing ? PlaybackState.PLAYING : PlaybackState.PAUSED,
                elapsedMicros / 1000,
                nanoTime - ageMicros * 1000,
                rate,
                switch ((int) modeOf(state, "shuffleMode", 26, "kMRMediaRemoteCommandInfoShuffleMode")) {
                    case 1 -> Shuffle.OFF;
                    case 2, 3 -> Shuffle.ON;
                    default -> Shuffle.UNKNOWN;
                },
                switch ((int) modeOf(state, "repeatMode", 25, "kMRMediaRemoteCommandInfoRepeatMode")) {
                    case 1 -> Repeat.OFF;
                    case 2 -> Repeat.ONE;
                    case 3 -> Repeat.ALL;
                    default -> Repeat.UNKNOWN;
                },
                Double.NaN,
                controls,
                cover.of(state.get("artworkData")),
                null);
    }

    /**
     * The controls the player has enabled, from the helper's list of supported commands. Older helpers send no list, so
     * every control is assumed then, less next when the player prohibits skipping.
     */
    static Set<Control> controlsOf(Map<String, Object> state) {
        Set<Control> controls = new HashSet<>();
        if (!(state.get("supportedCommands") instanceof List<?> commands)) {
            controls.addAll(List.of(
                    Control.PLAY,
                    Control.PAUSE,
                    Control.TOGGLE,
                    Control.STOP,
                    Control.PREVIOUS,
                    Control.SEEK,
                    Control.SHUFFLE,
                    Control.REPEAT));
            if (!Boolean.TRUE.equals(state.get("prohibitsSkip"))) {
                controls.add(Control.NEXT);
            }
            return controls;
        }
        for (Object entry : commands) {
            if (!(entry instanceof Map<?, ?> command) || !Boolean.TRUE.equals(command.get("enabled"))) {
                continue;
            }
            Control control =
                    switch (command.get("id") instanceof Double id ? id.intValue() : -1) {
                        case 0 -> Control.PLAY;
                        case 1 -> Control.PAUSE;
                        case 2 -> Control.TOGGLE;
                        case 3 -> Control.STOP;
                        case 4 -> Control.NEXT;
                        case 5 -> Control.PREVIOUS;
                        case 24 -> Control.SEEK;
                        case 25 -> Control.REPEAT;
                        case 26 -> Control.SHUFFLE;
                        default -> null;
                    };
            if (control != null) {
                controls.add(control);
            }
        }
        return controls;
    }

    /** A shuffle or repeat mode, from the metadata or else from the options of its supported command. 0 when unknown. */
    static long modeOf(Map<String, Object> state, String key, int commandId, String option) {
        long mode = micros(state.get(key));
        if (mode != 0 || !(state.get("supportedCommands") instanceof List<?> commands)) {
            return mode;
        }
        for (Object entry : commands) {
            if (entry instanceof Map<?, ?> command
                    && command.get("id") instanceof Double id
                    && id.intValue() == commandId
                    && command.get("options") instanceof Map<?, ?> options) {
                return micros(options.get(option));
            }
        }
        return 0;
    }

    private static String appOf(Map<String, Object> state) {
        String app = text(state.get("bundleIdentifier"));
        return app.isEmpty() ? text(state.get("parentApplicationBundleIdentifier")) : app;
    }

    /** The file name of a process, for players that are not application bundles. Empty when unknown. */
    private static String processName(long pid) {
        Process process = null;
        try {
            process = new ProcessBuilder("/bin/ps", "-p", Long.toString(pid), "-o", "comm=")
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                return "";
            }
            String line;
            try (BufferedReader in =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = in.readLine();
            }
            if (line == null) {
                return "";
            }
            return nameOf(line);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /** The last path element of a command path, at most 100 characters. */
    static String nameOf(String command) {
        String name = command.strip();
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }

    /** Whether the helper refused a command because the player does not support it or has it disabled. */
    static boolean rejected(String output) {
        return output.contains("is not supported by the current now playing application")
                || output.contains("is disabled with the current now playing application");
    }

    /** A whole number from a JSON number; 0 for anything else, including infinities. */
    private static long micros(Object value) {
        if (value instanceof Double d && !d.isNaN() && !d.isInfinite() && Math.abs(d) < 9e18) {
            return d.longValue();
        }
        return 0;
    }

    @Override
    public CompletableFuture<ControlResult> send(ControlRequest request) {
        String[] arguments =
                switch (request.control()) {
                    case PLAY -> new String[] {"send", "0"};
                    case PAUSE -> new String[] {"send", "1"};
                    case TOGGLE -> new String[] {"send", "2"};
                    case STOP -> new String[] {"send", "3"};
                    case NEXT -> new String[] {"send", "4"};
                    case PREVIOUS -> new String[] {"send", "5"};
                    case SEEK -> new String[] {"seek", Long.toString(Math.max(1, request.positionMillis() * 1000))};
                    case SHUFFLE -> new String[] {"shuffle", request.on() ? "3" : "1"};
                    case REPEAT ->
                        new String[] {
                            "repeat",
                            switch (request.repeat()) {
                                case ONE -> "2";
                                case ALL -> "3";
                                default -> "1";
                            }
                        };
                    default -> null;
                };
        if (arguments == null) {
            return CompletableFuture.completedFuture(
                    ControlResult.unsupported("macOS has no " + request.control() + " command for other applications"));
        }
        if (!request.sessionId().equals(sessionId)) {
            return CompletableFuture.completedFuture(
                    ControlResult.refused("that application is no longer the one playing"));
        }
        CompletableFuture<ControlResult> result = new CompletableFuture<>();
        ExecutorService executor;
        synchronized (lifecycle) {
            executor = commands;
        }
        if (executor == null) {
            result.complete(ControlResult.failed("not running"));
            return result;
        }
        try {
            long queued = System.nanoTime();
            executor.execute(() -> result.complete(
                    System.nanoTime() - queued > commandTimeoutMillis * 1_000_000L
                            ? ControlResult.failed("the helper was busy")
                            : run(arguments)));
        } catch (RejectedExecutionException e) {
            result.complete(ControlResult.failed("not running"));
        }
        return result;
    }

    private ControlResult run(String[] arguments) {
        Process process = null;
        try {
            process = new ProcessBuilder(command(arguments))
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            StringBuilder output = new StringBuilder();
            Process running = process;
            Thread drain = new Thread(() -> drain(running.getInputStream(), output), "orpheus-macos-errors");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(commandTimeoutMillis, TimeUnit.MILLISECONDS)) {
                return ControlResult.failed("the helper did not answer");
            }
            drain.join(500);
            if (process.exitValue() == 0) {
                return ControlResult.DONE;
            }
            synchronized (output) {
                if (rejected(output.toString())) {
                    return ControlResult.unsupported("the player does not accept it");
                }
                return ControlResult.failed(
                        output.length() == 0
                                ? "the helper exited with code " + process.exitValue()
                                : output.toString());
            }
        } catch (IOException e) {
            return ControlResult.failed("the helper could not be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ControlResult.failed("stopped");
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
