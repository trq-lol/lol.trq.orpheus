/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.ControlStatus;
import lol.trq.orpheus.MediaKind;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.SourceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacNowPlayingTest {
    private static final String PLAYING =
            "{\"type\":\"data\",\"diff\":false,\"payload\":{\"bundleIdentifier\":\"com.example.player\","
                    + "\"playing\":true,\"title\":\"\u6771\u4eac Song\",\"artist\":\"An Artist\",\"album\":\"An Album\","
                    + "\"durationMicros\":180000000,\"elapsedTimeMicros\":42000000,\"timestampEpochMicros\":1000000000000,"
                    + "\"playbackRate\":1,\"shuffleMode\":3,\"repeatMode\":1,\"isMusicApp\":true,\"trackNumber\":4,"
                    + "\"uniqueIdentifier\":\"id-1\"}}";

    @TempDir
    Path dir;

    private final Recorder sink = new Recorder();
    private MacNowPlaying source;

    @AfterEach
    void stop() {
        if (source != null) {
            source.stop();
        }
    }

    private MacNowPlaying fake(String... script) throws Exception {
        Path scriptFile = dir.resolve("script.txt");
        Files.write(scriptFile, List.of(script));
        Path log = dir.resolve("log.txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        source = MacNowPlaying.withCommand(helper -> {
            List<String> command = new ArrayList<>(List.of(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    FakeAdapter.class.getName(),
                    scriptFile.toString(),
                    log.toString()));
            command.addAll(helper);
            return command;
        });
        // starting a child JVM takes a while on a busy machine
        source.commandTimeoutMillis = 60_000;
        return source;
    }

    private List<String> log() throws Exception {
        Path log = dir.resolve("log.txt");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private static SessionUpdate session(Map<String, Object> state) {
        return MacNowPlaying.session(state, new MacNowPlaying.CoverCache(), 5_000_000_000L, 1_000_002_000L);
    }

    @Test
    void mapsTheHelperFieldsAndDatesThePositionByItsTimestamp() {
        Map<String, Object> state = new HashMap<>();
        assertTrue(MacNowPlaying.apply(PLAYING, state));
        SessionUpdate s = session(state);
        assertEquals("com.example.player", s.sessionId());
        assertEquals("\u6771\u4eac Song", s.track().title());
        assertEquals(List.of("An Artist"), s.track().artists());
        assertEquals(180_000, s.track().durationMillis());
        assertEquals("id-1", s.track().id());
        assertEquals(MediaKind.MUSIC, s.track().kind());
        assertEquals(PlaybackState.PLAYING, s.state());
        assertEquals(42_000, s.positionMillis());
        assertEquals(
                5_000_000_000L - 2_000_000_000L,
                s.measuredNanos(),
                "the timestamp is two seconds before the wall clock");
        assertEquals(Shuffle.ON, s.shuffle());
        assertEquals(Repeat.OFF, s.repeat());
        assertTrue(s.controls().contains(Control.NEXT));
        assertFalse(s.controls().contains(Control.VOLUME));
    }

    @Test
    void diffsMergeNullsRemoveAndFullPayloadsReplace() {
        Map<String, Object> state = new HashMap<>();
        MacNowPlaying.apply(PLAYING, state);
        assertTrue(MacNowPlaying.apply(
                "{\"type\":\"data\",\"diff\":true,\"payload\":{\"playing\":false,\"album\":null,\"prohibitsSkip\":true}}",
                state));
        SessionUpdate paused = session(state);
        assertEquals(PlaybackState.PAUSED, paused.state());
        assertEquals("", paused.track().album());
        assertEquals("\u6771\u4eac Song", paused.track().title(), "keys a diff does not mention are kept");
        assertFalse(paused.controls().contains(Control.NEXT));

        assertTrue(MacNowPlaying.apply(
                "{\"type\":\"data\",\"diff\":false,\"payload\":{\"playing\":true,\"title\":\"Other\"}}", state));
        SessionUpdate other = session(state);
        assertEquals("unknown", other.app().id(), "a payload without any bundle id still gets an id");
        assertEquals(List.of(), other.track().artists(), "a full payload drops what the previous one had");

        assertTrue(MacNowPlaying.apply("{\"diff\":true,\"payload\":{\"playing\":null,\"title\":null}}", state));
        assertNull(session(state), "a diff that removes every key leaves nothing playing");
        assertTrue(MacNowPlaying.apply("{\"type\":\"data\",\"diff\":false,\"payload\":{}}", state));
        assertNull(session(state), "an empty payload means nothing is playing");
        assertFalse(MacNowPlaying.apply("not json", state));
        assertFalse(MacNowPlaying.apply("[1,2]", state));
        assertFalse(MacNowPlaying.apply("{\"payload\":5}", state));
    }

    @Test
    void oddNumbersAndArtworkAreHandled() {
        Map<String, Object> state = new HashMap<>();
        MacNowPlaying.apply(
                "{\"diff\":false,\"payload\":{\"playing\":true,\"title\":\"Live\",\"durationMicros\":1e999,\"elapsedTimeMicros\":-5,"
                        + "\"timestampEpochMicros\":9e30,\"playbackRate\":0,\"mediaType\":\"MRMediaRemoteMediaTypeVideo\"}}",
                state);
        SessionUpdate live = session(state);
        assertEquals(-1, live.track().durationMillis(), "an infinite length is an unknown length");
        assertEquals(0, live.positionMillis());
        assertEquals(5_000_000_000L, live.measuredNanos(), "an absurd timestamp is ignored");
        assertEquals(1.0, live.rate());
        assertEquals(MediaKind.VIDEO, live.track().kind());

        state.put("timestampEpochMicros", 1_000_002_000_000.0 + 10_000_000);
        assertEquals(5_000_000_000L, session(state).measuredNanos(), "a timestamp from the future is ignored");
        state.put("timestampEpochMicros", 1_000_002_000_000.0 - 3 * 86_400_000_000.0);
        assertEquals(5_000_000_000L, session(state).measuredNanos(), "a timestamp from days ago is ignored");

        MacNowPlaying.CoverCache cache = new MacNowPlaying.CoverCache();
        String art = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        CoverData first = cache.of(art);
        assertEquals(3, first.bytes().length);
        assertSame(first, cache.of(art), "the same picture must keep the same instance");
        assertNull(cache.of("%%% not base64 %%%"));
        assertNull(cache.of(null));
        assertNotNull(cache.of(art));
    }

    @Test
    void streamsFromTheChildProcessAndSkipsGarbage() throws Exception {
        fake("this is not json", "{\"broken\":", PLAYING, "!hold").start(sink);
        sink.awaitStatus(s -> s.state() == SourceStatus.State.READY);
        SessionUpdate s = sink.awaitSessions(list -> list.size() == 1).get(0);
        assertEquals("An Artist", s.track().artistLine());
    }

    @Test
    void commandsRunTheHelperWithTheRightArguments() throws Exception {
        fake(PLAYING, "!hold").start(sink);
        sink.awaitSessions(list -> list.size() == 1);
        String session = "com.example.player";
        assertTrue(send(new ControlRequest(session, Control.SEEK, 42_000, 0, false, Repeat.OFF))
                .done());
        assertTrue(send(new ControlRequest(session, Control.SHUFFLE, 0, 0, true, Repeat.OFF))
                .done());
        assertTrue(send(new ControlRequest(session, Control.REPEAT, 0, 0, false, Repeat.ALL))
                .done());
        assertTrue(send(ControlRequest.of(session, Control.NEXT)).done());
        assertTrue(send(ControlRequest.of(session, Control.TOGGLE)).done());
        assertEquals(List.of("stream started", "seek 42000000", "shuffle 3", "repeat 3", "send 4", "send 2"), log());
        assertEquals(
                ControlStatus.UNSUPPORTED,
                send(ControlRequest.of(session, Control.VOLUME)).status());
        assertEquals(
                ControlStatus.REFUSED,
                send(ControlRequest.of("com.example.other", Control.PLAY)).status());
    }

    @Test
    void aFailingCommandReportsWhatTheHelperSaid() throws Exception {
        fake(PLAYING, "!fail-commands", "!hold").start(sink);
        sink.awaitSessions(list -> list.size() == 1);
        ControlResult result = send(ControlRequest.of("com.example.player", Control.PLAY));
        assertEquals(ControlStatus.FAILED, result.status());
        assertTrue(result.reason().contains("Invalid command"), result.reason());
    }

    @Test
    void aHelperThatDiesIsReportedAndStartedAgain() throws Exception {
        fake(PLAYING, "!err Operation not permitted", "!exit 4").start(sink);
        sink.awaitStatus(s -> s.state() == SourceStatus.State.READY);
        SourceStatus down = sink.awaitStatus(s -> s.state() == SourceStatus.State.UNAVAILABLE);
        assertTrue(down.reason().contains("Operation not permitted"), down.reason());
        sink.awaitStatus(s -> s.state() == SourceStatus.State.READY);
        assertTrue(log().size() >= 2, "the helper must have been started a second time");
    }

    @Test
    void stopKillsTheChildAndTheThreads() throws Exception {
        fake(PLAYING, "!hold").start(sink);
        sink.awaitSessions(list -> list.size() == 1);
        assertTrue(helpersAlive() > 0);
        source.stop();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (helpersAlive() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(0, helpersAlive(), "the helper survived stop");
        assertEquals(
                0,
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.isAlive() && t.getName().startsWith("orpheus-macos"))
                        .count());
        assertEquals(
                ControlStatus.REFUSED,
                send(ControlRequest.of("com.example.player", Control.PLAY)).status());
    }

    @Test
    void anotherOperatingSystemIsReportedNotThrown() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().startsWith("mac"));
        source = MacNowPlaying.create();
        source.start(sink);
        assertEquals(
                "not running on macOS",
                sink.awaitStatus(s -> s.state() == SourceStatus.State.UNAVAILABLE)
                        .reason());
    }

    /** Child JVMs still running. Console hosts and other system helpers of a child are not ours to count. */
    private static long helpersAlive() {
        return ProcessHandle.current()
                .descendants()
                .filter(ProcessHandle::isAlive)
                .filter(p -> p.info().command().map(c -> c.contains("java")).orElse(false))
                .count();
    }

    private ControlResult send(ControlRequest request) throws Exception {
        return source.send(request).get(30, TimeUnit.SECONDS);
    }
}
