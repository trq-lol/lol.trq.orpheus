/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import static lol.trq.orpheus.testkit.FakeSource.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lol.trq.orpheus.AppRules;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.DisplayRules;
import lol.trq.orpheus.Playback;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.Snapshot;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.cover.Cover;
import lol.trq.orpheus.cover.CoverCache;
import lol.trq.orpheus.event.CoverReady;
import lol.trq.orpheus.event.LyricsChanged;
import lol.trq.orpheus.event.OrpheusEvent;
import lol.trq.orpheus.event.Seeked;
import lol.trq.orpheus.event.SessionsChanged;
import lol.trq.orpheus.event.StateChanged;
import lol.trq.orpheus.event.TrackChanged;
import lol.trq.orpheus.lyrics.LyricLine;
import lol.trq.orpheus.lyrics.Lyrics;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.testkit.FakeSource;
import org.junit.jupiter.api.Test;

class EngineTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;

    private final List<OrpheusEvent> events = new ArrayList<>();
    private final List<CoverData> coverRequests = new ArrayList<>();
    private final List<Long> lyricRequests = new ArrayList<>();
    private final List<Long> wakes = new ArrayList<>();
    private long now = 100 * SECOND;

    private final Engine.Host host = new Engine.Host() {
        @Override
        public void publish(Snapshot snapshot) {}

        @Override
        public void emit(OrpheusEvent event) {
            events.add(event);
        }

        @Override
        public void decodeCover(CoverData data) {
            coverRequests.add(data);
        }

        @Override
        public void findLyrics(long generation, Track track) {
            lyricRequests.add(generation);
        }

        @Override
        public void wakeAt(long nanos) {
            wakes.add(nanos);
        }
    };

    private final FakeSource os = new FakeSource("os", true);
    private final FakeSource service = new FakeSource("service", false);

    private Engine engine(AppRules rules, String... shareable) {
        return new Engine(List.of(os, service), rules, Set.of(shareable), host, () -> now, new CoverCache(1 << 20));
    }

    private long count(Class<?> type) {
        return events.stream().filter(type::isInstance).count();
    }

    private static Lyrics someLyrics() {
        return new Lyrics(Lyrics.Kind.LINE, List.of(new LyricLine(0, -1, "la", List.of(), "", "")), 0, "", 1);
    }

    @Test
    void playingBeatsPausedAndTheNewestChangeBreaksTies() {
        Engine engine = engine(AppRules.all());
        engine.sessions(
                0,
                List.of(
                        session("a").state(PlaybackState.PAUSED).build(),
                        session("b").build()));
        assertEquals("b", engine.snapshot().playback().sessionId());

        Engine other = engine(AppRules.all());
        other.sessions(0, List.of(session("a").state(PlaybackState.PAUSED).build()));
        now += SECOND;
        other.sessions(
                0,
                List.of(
                        session("a").state(PlaybackState.PAUSED).build(),
                        session("b").state(PlaybackState.PAUSED).build()));
        assertEquals(
                "a", other.snapshot().playback().sessionId(), "a later paused session must not steal the selection");
    }

    @Test
    void aShortPauseDoesNotFlipToAnotherPlayer() {
        Engine engine = engine(AppRules.all());
        SessionUpdate b = session("b").track("Other", "Band").build();
        engine.sessions(0, List.of(session("a").build()));
        now += SECOND;
        engine.sessions(0, List.of(session("a").build(), b));
        assertEquals("a", engine.snapshot().playback().sessionId(), "two playing sessions: keep the current one");

        now += SECOND;
        engine.sessions(0, List.of(session("a").state(PlaybackState.PAUSED).build(), b));
        assertEquals("a", engine.snapshot().playback().sessionId());
        assertEquals(List.of(now + Engine.STICKY_NANOS), wakes, "the engine must ask to be woken when the grace ends");

        now += Engine.STICKY_NANOS - MILLI;
        engine.refresh();
        assertEquals("a", engine.snapshot().playback().sessionId());
        now += MILLI;
        engine.refresh();
        assertEquals("b", engine.snapshot().playback().sessionId());
    }

    @Test
    void resumingWithinTheGraceKeepsTheSelection() {
        Engine engine = engine(AppRules.all());
        SessionUpdate b = session("b").track("Other", "Band").build();
        engine.sessions(0, List.of(session("a").build()));
        engine.sessions(0, List.of(session("a").state(PlaybackState.PAUSED).build(), b));
        now += SECOND;
        engine.sessions(0, List.of(session("a").build(), b));
        now += 10 * SECOND;
        engine.refresh();
        assertEquals("a", engine.snapshot().playback().sessionId());
    }

    @Test
    void aVanishedSelectionIsReplacedAtOnce() {
        Engine engine = engine(AppRules.all());
        engine.sessions(
                0,
                List.of(
                        session("a").build(),
                        session("b").state(PlaybackState.PAUSED).build()));
        engine.sessions(0, List.of(session("b").state(PlaybackState.PAUSED).build()));
        assertEquals("b", engine.snapshot().playback().sessionId());
        engine.sessions(0, List.of());
        assertFalse(engine.snapshot().playback().isPresent());
    }

    @Test
    void foreignSessionsObeyTheRulesButOwnSourcesDoNot() {
        Engine engine = engine(AppRules.none().allow("goodplayer"));
        engine.sessions(0, List.of(session("tab").app("chrome.exe").build()));
        assertFalse(engine.snapshot().playback().isPresent(), "a browser must stay hidden");
        assertEquals(1, engine.sessions().size());
        assertFalse(engine.sessions().get(0).visible());

        engine.sessions(1, List.of(session("mine").app("whatever").build()));
        assertEquals("service", engine.snapshot().playback().sourceId());

        engine.rules(AppRules.all());
        engine.sessions(1, List.of());
        assertEquals(
                "tab", engine.snapshot().playback().sessionId(), "changing the rules takes effect without new reports");
    }

    @Test
    void pinRestrictsTheChoiceAndShowsNothingWhenThePinnedAppIsAway() {
        Engine engine = engine(AppRules.all());
        engine.sessions(
                0,
                List.of(
                        session("a").app("vlc.exe").build(),
                        session("b")
                                .app("spotify.exe")
                                .state(PlaybackState.PAUSED)
                                .build()));
        engine.pin("os", "spotify");
        assertEquals("b", engine.snapshot().playback().sessionId());
        engine.sessions(0, List.of(session("a").app("vlc.exe").build()));
        assertFalse(engine.snapshot().playback().isPresent());
        engine.pin(null, null);
        assertEquals("a", engine.snapshot().playback().sessionId());
    }

    @Test
    void sourcesDescribingTheSameSongAreMerged() {
        Engine engine = engine(AppRules.all());
        CoverData art = CoverData.of(new byte[] {1, 2, 3});
        engine.sessions(
                0,
                List.of(session("os1")
                        .track("Night Drive", "The Examples")
                        .can(Control.PAUSE)
                        .build()));
        engine.sessions(
                1,
                List.of(session("svc")
                        .track(new Track(
                                "id-9",
                                "Night Drive (Remastered)",
                                List.of("Examples"),
                                "Roads",
                                "",
                                201_000,
                                4,
                                null,
                                null))
                        .shuffle(Shuffle.ON)
                        .cover(art)
                        .can(Control.PAUSE, Control.SHUFFLE)
                        .build()));
        Playback p = engine.snapshot().playback();
        assertEquals("os", p.sourceId());
        assertEquals("Night Drive", p.track().title(), "the primary keeps its own text");
        assertEquals("Roads", p.track().album());
        assertEquals(201_000, p.track().durationMillis());
        assertEquals(201_000, p.timeline().durationMillis());
        assertEquals(Shuffle.ON, p.shuffle());
        assertEquals(art.key(), p.coverKey());
        assertSame(os, engine.route(p, Control.PAUSE).source(), "a control both have goes to the primary");
        assertSame(service, engine.route(p, Control.SHUFFLE).source(), "an extra control goes to whoever offers it");
        assertEquals("svc", engine.route(p, Control.SHUFFLE).sessionId());
    }

    @Test
    void differentSongsAreNeverMixed() {
        Engine engine = engine(AppRules.all());
        engine.sessions(
                0, List.of(session("os1").track("Night Drive", "The Examples").build()));
        engine.sessions(
                1,
                List.of(session("svc")
                        .track(new Track(
                                null, "Morning Walk", List.of("Examples"), "Roads", "", 150_000, 0, null, null))
                        .cover(CoverData.of(new byte[] {9}))
                        .can(Control.NEXT)
                        .build()));
        Playback p = engine.snapshot().playback();
        assertEquals("", p.track().album());
        assertNull(p.coverKey());
        assertNull(engine.route(p, Control.NEXT));

        // the service moves to the same song: merge; then the primary skips ahead: the merge must end in the same step
        engine.sessions(
                1,
                List.of(session("svc")
                        .track(new Track(null, "Night Drive", List.of("The Examples"), "Roads", "", -1, 0, null, null))
                        .build()));
        assertEquals("Roads", engine.snapshot().playback().track().album());
        engine.sessions(
                0, List.of(session("os1").track("Third Song", "The Examples").build()));
        assertEquals("", engine.snapshot().playback().track().album());
    }

    @Test
    void sharingAndDisplayRulesTakeTheStrictestOfTheMergedParts() {
        service.display = new DisplayRules(true, "From Service", "https://example.invalid");
        Engine engine = engine(AppRules.all(), "os");
        engine.sessions(0, List.of(session("os1").build()));
        assertTrue(engine.snapshot().playback().shareable());
        engine.sessions(1, List.of(session("svc").build()));
        Playback merged = engine.snapshot().playback();
        assertFalse(merged.shareable(), "one part that is not shareable makes the whole not shareable");
        assertTrue(merged.display().artworkUnmodified());
        assertEquals("From Service", merged.display().attribution());
    }

    @Test
    void smallDriftKeepsTheAnchorAndRealJumpsReplaceIt() {
        Engine engine = engine(AppRules.all());
        engine.sessions(0, List.of(session("a").at(10_000, now).build()));
        var first = engine.snapshot().playback().timeline();

        now += 5 * SECOND;
        engine.sessions(0, List.of(session("a").at(15_060, now).build()));
        assertSame(first, engine.snapshot().playback().timeline(), "60 ms of drift is noise");
        assertEquals(0, count(Seeked.class));

        now += 5 * SECOND;
        engine.sessions(0, List.of(session("a").at(20_400, now).build()));
        assertEquals(
                20_400, engine.snapshot().playback().timeline().positionMillisAt(now), "400 ms off must be corrected");
        assertEquals(0, count(Seeked.class), "but it is not a seek");

        now += SECOND;
        engine.sessions(0, List.of(session("a").at(90_000, now).build()));
        assertEquals(90_000, engine.snapshot().playback().timeline().positionMillisAt(now));
        assertEquals(1, count(Seeked.class));
        assertEquals(0, count(TrackChanged.class) - 1, "only the first report was a track change");
    }

    @Test
    void aBackwardSeekOfOneSecondIsFollowed() {
        Engine engine = engine(AppRules.all());
        engine.sessions(0, List.of(session("a").at(30_000, now).build()));
        now += 2 * SECOND;
        engine.sessions(0, List.of(session("a").at(31_000, now).build()));
        assertEquals(31_000, engine.snapshot().playback().timeline().positionMillisAt(now));
    }

    @Test
    void pausingFreezesThePositionAndRateChangesApply() {
        Engine engine = engine(AppRules.all());
        engine.sessions(0, List.of(session("a").at(10_000, now).build()));
        now += 4 * SECOND;
        engine.sessions(
                0,
                List.of(session("a").state(PlaybackState.PAUSED).at(14_000, now).build()));
        assertEquals(14_000, engine.snapshot().playback().timeline().positionMillisAt(now + 60 * SECOND));
        assertEquals(1, count(StateChanged.class) - 1);

        now += 60 * SECOND;
        engine.sessions(0, List.of(session("a").rate(2).at(14_000, now).build()));
        assertEquals(34_000, engine.snapshot().playback().timeline().positionMillisAt(now + 10 * SECOND));
    }

    @Test
    void anUnchangedReportChangesNothing() {
        Engine engine = engine(AppRules.all());
        SessionUpdate same = session("a").at(10_000, now).build();
        engine.sessions(0, List.of(same));
        Snapshot before = engine.snapshot();
        int eventsBefore = events.size();
        now += 3 * SECOND;
        engine.sessions(0, List.of(session("a").at(10_000, now - 3 * SECOND).build()));
        assertSame(before, engine.snapshot());
        assertEquals(eventsBefore, events.size());
    }

    @Test
    void controlsFollowCapabilityChangesMidSession() {
        Engine engine = engine(AppRules.all());
        engine.sessions(0, List.of(session("a").can(Control.PAUSE, Control.SEEK).build()));
        assertTrue(engine.snapshot().playback().can(Control.SEEK));
        engine.sessions(0, List.of(session("a").can(Control.PAUSE).build()));
        Playback p = engine.snapshot().playback();
        assertFalse(p.can(Control.SEEK));
        assertNull(engine.route(p, Control.SEEK));
        assertNotNull(engine.route(p, Control.PAUSE));
    }

    @Test
    void lyricsOfAnOldTrackNeverReachANewOne() {
        Engine engine = engine(AppRules.all());
        engine.sessions(0, List.of(session("a").track("One", "X").build()));
        long first = lyricRequests.get(0);
        engine.sessions(0, List.of(session("a").track("Two", "X").build()));
        engine.lyricsFound(first, someLyrics());
        assertSame(Lyrics.NONE, engine.snapshot().lyrics(), "a late answer for the previous track must be dropped");

        engine.lyricsFound(lyricRequests.get(1), someLyrics());
        assertEquals(1, engine.snapshot().lyrics().size());
        assertEquals(1, count(LyricsChanged.class));

        engine.sessions(0, List.of(session("a").track("Three", "X").build()));
        assertSame(Lyrics.NONE, engine.snapshot().lyrics(), "a track change clears the lyrics in the same snapshot");
    }

    @Test
    void lyricOffsetIsRememberedPerSong() {
        Engine engine = engine(AppRules.all());
        engine.sessions(0, List.of(session("a").track("One", "X").build()));
        engine.lyricsFound(lyricRequests.get(0), someLyrics());
        engine.lyricsOffset(250);
        assertEquals(250, engine.snapshot().lyrics().offsetMillis());

        engine.sessions(0, List.of(session("a").track("Two", "X").build()));
        engine.lyricsFound(lyricRequests.get(1), someLyrics());
        assertEquals(0, engine.snapshot().lyrics().offsetMillis());

        engine.sessions(0, List.of(session("a").track("One", "X").build()));
        engine.lyricsFound(lyricRequests.get(2), someLyrics());
        assertEquals(250, engine.snapshot().lyrics().offsetMillis());
    }

    @Test
    void aCoverIsDecodedOnceAndOnlyShownForItsOwnTrack() {
        Engine engine = engine(AppRules.all());
        CoverData one = CoverData.of(new byte[] {1});
        CoverData two = CoverData.of(new byte[] {2});
        engine.sessions(0, List.of(session("a").track("One", "X").cover(one).build()));
        engine.sessions(
                0, List.of(session("a").track("One", "X").cover(one).at(5, now).build()));
        assertEquals(1, coverRequests.size(), "the same cover must not be decoded twice while pending");

        engine.sessions(0, List.of(session("a").track("Two", "X").cover(two).build()));
        engine.coverDecoded(one.key(), new Cover(one.key(), 1, 1, new byte[4], 0, new int[0]));
        assertNull(engine.snapshot().cover(), "the previous cover must not appear on the new track");

        engine.coverDecoded(two.key(), new Cover(two.key(), 1, 1, new byte[4], 0, new int[0]));
        assertEquals(two.key(), engine.snapshot().cover().key());
        assertEquals(1, count(CoverReady.class));

        engine.sessions(0, List.of(session("a").track("One", "X").cover(one).build()));
        assertEquals(one.key(), engine.snapshot().cover().key(), "a cached cover is in the very first snapshot");
        assertEquals(2, coverRequests.size());
    }

    @Test
    void aSourceThatStopsBeingReadyLosesItsSessions() {
        Engine engine = engine(AppRules.all());
        engine.sessions(0, List.of(session("a").build()));
        engine.status(0, lol.trq.orpheus.source.SourceStatus.unavailable("bus went away"));
        assertFalse(engine.snapshot().playback().isPresent());
        assertTrue(count(SessionsChanged.class) >= 2);
    }

    @Test
    void commandsForASessionThatIsNotSelectedStillFindIt() {
        Engine engine = engine(AppRules.all());
        engine.sessions(
                0,
                List.of(
                        session("a").can(Control.PAUSE).build(),
                        session("b")
                                .state(PlaybackState.PAUSED)
                                .can(Control.PLAY)
                                .build()));
        Playback a = engine.snapshot().playback();
        engine.sessions(0, List.of(session("b").can(Control.PLAY).build()));
        assertNull(engine.route(a, Control.PAUSE));
        assertFalse(engine.alive(a));
        Playback b = engine.snapshot().playback();
        engine.sessions(
                0, List.of(session("b").can(Control.PLAY).build(), session("c").build()));
        assertEquals("b", engine.route(b, Control.PLAY).sessionId());
    }
}
