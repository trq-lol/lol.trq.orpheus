/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.ControlStatus;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.linux.dbus.Message;
import lol.trq.orpheus.linux.dbus.Variant;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.SourceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MprisSourceTest {
    private static final String NAME = "org.mpris.MediaPlayer2.fake.instance77";

    private FakeBus bus;
    private MprisSource source;
    private final Recorder sink = new Recorder();

    @BeforeEach
    void open() throws Exception {
        bus = new FakeBus();
        bus.players.put(NAME, FakeBus.player("東京 مرحبا"));
        bus.players.put("org.mpris.MediaPlayer2.playerctld", FakeBus.player("proxy"));
        bus.players.put("org.unrelated.Service", FakeBus.player("not a player"));
        source = MprisSource.create(bus.environment());
    }

    @AfterEach
    void close() throws Exception {
        source.stop();
        bus.close();
    }

    private SessionUpdate started() throws Exception {
        source.start(sink);
        return sink.awaitSessions(s -> s.size() == 1 && s.get(0).app().name().equals("Fake Player"))
                .get(0);
    }

    private ControlResult send(ControlRequest request) throws Exception {
        return source.send(request).get(15, TimeUnit.SECONDS);
    }

    private Message lastCall() {
        return bus.calls.get(bus.calls.size() - 1);
    }

    @Test
    void reportsOnePlayerWithEverythingMapped() throws Exception {
        SessionUpdate s = started();
        assertEquals(NAME, s.sessionId());
        assertEquals("fake", s.app().id(), "the instance suffix must not be part of the app id");
        assertEquals("東京 مرحبا", s.track().title());
        assertEquals(List.of("Artist One", "Artist Two"), s.track().artists());
        assertEquals(180_000, s.track().durationMillis());
        assertEquals("/track/1", s.track().id());
        assertEquals(PlaybackState.PLAYING, s.state());
        assertEquals(42_000, s.positionMillis());
        assertEquals(Shuffle.OFF, s.shuffle());
        assertEquals(Repeat.OFF, s.repeat());
        assertEquals(0.8, s.volume());
        assertTrue(s.controls()
                .containsAll(Set.of(
                        Control.PLAY,
                        Control.PAUSE,
                        Control.TOGGLE,
                        Control.SEEK,
                        Control.NEXT,
                        Control.SHUFFLE,
                        Control.REPEAT,
                        Control.RATE,
                        Control.VOLUME)));
        assertFalse(s.controls().contains(Control.PREVIOUS), "CanGoPrevious is false");
        assertFalse(s.controls().contains(Control.MUTE));
    }

    @Test
    void followsSignalsFromThePlayerAndIgnoresStrangers() throws Exception {
        started();
        bus.signal(
                ":1.999",
                Message.signal("/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Seeked", "x", 1_000_000L));
        bus.signal(
                ":1.999",
                Message.signal(
                        "/org/mpris/MediaPlayer2",
                        "org.freedesktop.DBus.Properties",
                        "PropertiesChanged",
                        "sa{sv}as",
                        "org.mpris.MediaPlayer2.Player",
                        Map.of("Volume", new Variant("d", 0.1)),
                        List.of()));
        bus.changed("PlaybackStatus", new Variant("s", "Paused"));
        SessionUpdate paused = sink.awaitSessions(s -> s.size() == 1 && s.get(0).state() == PlaybackState.PAUSED)
                .get(0);
        assertEquals(42_000, paused.positionMillis(), "a Seeked signal from another connection must be ignored");
        assertEquals(0.8, paused.volume(), "a property change from another connection must be ignored");

        bus.signal(
                FakeBus.OWNER,
                Message.signal("/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Seeked", "x", 99_000_000L));
        sink.awaitSessions(s -> s.size() == 1 && s.get(0).positionMillis() == 99_000);

        bus.changed("CanSeek", new Variant("b", false));
        sink.awaitSessions(s -> s.size() == 1 && !s.get(0).controls().contains(Control.SEEK));
    }

    @Test
    void commandsBecomeTheRightCalls() throws Exception {
        started();
        assertTrue(send(new ControlRequest(NAME, Control.SEEK, 30_000, 0, false, Repeat.OFF))
                .done());
        assertEquals("SetPosition", lastCall().member());
        assertEquals(List.of("/track/1", 30_000_000L), lastCall().body());

        assertTrue(send(new ControlRequest(NAME, Control.SHUFFLE, 0, 0, true, Repeat.OFF))
                .done());
        assertEquals("Set", lastCall().member());
        assertEquals(
                List.of("org.mpris.MediaPlayer2.Player", "Shuffle", true),
                lastCall().body());

        assertTrue(send(new ControlRequest(NAME, Control.REPEAT, 0, 0, false, Repeat.ALL))
                .done());
        assertEquals("Playlist", lastCall().body().get(2));
        assertTrue(send(new ControlRequest(NAME, Control.RATE, 0, 1.25, false, Repeat.OFF))
                .done());
        assertEquals(1.25, lastCall().body().get(2));
        assertTrue(send(ControlRequest.of(NAME, Control.TOGGLE)).done());
        assertEquals("PlayPause", lastCall().member());

        ControlResult next = send(ControlRequest.of(NAME, Control.NEXT));
        assertEquals(ControlStatus.REFUSED, next.status(), "an error reply from the player is a refusal");
        assertTrue(next.reason().contains("end of the queue"));
        assertEquals(
                ControlStatus.UNSUPPORTED,
                send(ControlRequest.of(NAME, Control.FAST_FORWARD)).status());
        assertEquals(
                ControlStatus.REFUSED,
                send(ControlRequest.of("org.mpris.MediaPlayer2.gone", Control.PLAY))
                        .status());
    }

    @Test
    void seekFallsBackToARelativeJumpWithoutAUsableTrackId() throws Exception {
        Map<String, Object> properties = FakeBus.player("no id");
        properties.put(
                "Metadata",
                new Variant(
                        "a{sv}",
                        Map.of(
                                "xesam:title",
                                new Variant("s", "no id"),
                                "mpris:trackid",
                                new Variant("s", "not-a-path"))));
        properties.put("PlaybackStatus", new Variant("s", "Paused"));
        bus.players.put(NAME, properties);
        started();
        assertTrue(send(new ControlRequest(NAME, Control.SEEK, 50_000, 0, false, Repeat.OFF))
                .done());
        assertEquals("Seek", lastCall().member());
        assertEquals(List.of(8_000_000L), lastCall().body(), "from 42 s to 50 s is a jump of 8 s");
    }

    @Test
    void aPlayerThatLeavesTheBusDisappears() throws Exception {
        started();
        bus.players.remove(NAME);
        bus.nameOwnerChanged(NAME, FakeBus.OWNER, "");
        sink.awaitSessions(List::isEmpty);
        assertEquals(
                ControlStatus.REFUSED,
                send(ControlRequest.of(NAME, Control.PLAY)).status());

        bus.players.put(NAME, FakeBus.player("back again"));
        bus.nameOwnerChanged(NAME, "", FakeBus.OWNER);
        sink.awaitSessions(s -> s.size() == 1 && s.get(0).track().title().equals("back again"));
    }

    @Test
    void aSilentPlayerFailsTheCommandInsteadOfHangingIt() throws Exception {
        started();
        bus.silent = true;
        ControlResult result = send(ControlRequest.of(NAME, Control.PAUSE));
        assertEquals(ControlStatus.FAILED, result.status());
    }

    @Test
    void reconnectsAfterTheBusDropsTheConnection() throws Exception {
        started();
        bus.drop();
        sink.awaitStatus(s -> s.state() == SourceStatus.State.UNAVAILABLE);
        sink.awaitSessions(s -> s.size() == 1);
        sink.awaitStatus(s -> s.state() == SourceStatus.State.READY);
        assertEquals(2, bus.connections.get());
    }

    @Test
    void anUnusableBusIsReportedOnceAndNothingKeepsRunning() throws Exception {
        MprisSource other = MprisSource.create(Map.of("DBUS_SESSION_BUS_ADDRESS", "unix:abstract=/tmp/dbus-x"));
        other.start(sink);
        SourceStatus status = sink.awaitStatus(s -> s.state() == SourceStatus.State.UNAVAILABLE);
        assertTrue(status.reason().contains("abstract"));
        other.stop();

        started();
        source.stop();
        assertEquals(
                0,
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.isAlive()
                                && (t.getName().equals("orpheus-mpris")
                                        || t.getName().equals("orpheus-dbus")))
                        .count());
        assertEquals(
                ControlStatus.FAILED,
                send(ControlRequest.of(NAME, Control.PLAY)).status());
    }

    @Test
    void artIsLoadedOnlyFromInlineDataAndRegularFiles(@TempDir Path dir) throws Exception {
        byte[] image = {1, 2, 3, 4, 5};
        assertEquals(
                5,
                MprisSource.loadArt(
                                "data:image/png;base64," + Base64.getEncoder().encodeToString(image))
                        .bytes()
                        .length);
        Path file = dir.resolve("cover.png");
        Files.write(file, image);
        assertNotNull(MprisSource.loadArt(file.toUri().toString()));
        assertNull(MprisSource.loadArt(dir.toUri().toString()), "a directory is not art");
        assertNull(MprisSource.loadArt(dir.resolve("missing.png").toUri().toString()));
        assertNull(MprisSource.loadArt("https://example.invalid/cover.png"), "remote art is never fetched");
        assertNull(MprisSource.loadArt("data:image/png,rawtext"));
        assertNull(MprisSource.loadArt("file:not a uri"));
    }
}
