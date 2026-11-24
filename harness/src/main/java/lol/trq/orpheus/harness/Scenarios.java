/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import lol.trq.orpheus.AppRules;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.ControlStatus;
import lol.trq.orpheus.Orpheus;
import lol.trq.orpheus.Playback;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Snapshot;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.cover.Cover;
import lol.trq.orpheus.event.PlaybackChanged;
import lol.trq.orpheus.lyrics.LocalLyrics;
import lol.trq.orpheus.lyrics.Lyrics;
import lol.trq.orpheus.source.PlaybackUpdate;
import lol.trq.orpheus.source.PushSource;
import lol.trq.orpheus.source.SourceStatus;

/** The live checks: what the library sees of a real operating system session, and whether every control arrives. */
final class Scenarios {
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }

    interface Body {
        void run(Map<String, Object> numbers) throws Exception;
    }

    private static final String TITLE = "Harness Track One";
    private static final String ARTIST = "Harness Artist";
    private static final long SECOND = 1_000_000_000L;

    private final Rig rig;
    private Publisher publisher;

    Scenarios(Rig rig) {
        this.rig = rig;
    }

    Map<String, Body> all() {
        Map<String, Body> all = new LinkedHashMap<>();
        all.put("metadata", this::metadata);
        all.put("change_latency", this::changeLatency);
        all.put("cover", this::cover);
        all.put("progress", this::progress);
        all.put("controls", this::controls);
        all.put("unsupported_controls", this::unsupportedControls);
        all.put("rapid_changes", this::rapidChanges);
        all.put("text", this::text);
        all.put("missing_metadata", this::missingMetadata);
        all.put("filtering", this::filtering);
        all.put("player_crash", this::playerCrash);
        all.put("source_switching", this::sourceSwitching);
        all.put("lyrics", this::lyrics);
        all.put("start_stop", this::startStop);
        return all;
    }

    void open() throws Exception {
        publisher = rig.publisher();
    }

    void close() {
        if (publisher != null) {
            publisher.close();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new Failure(message);
        }
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    private Orpheus.Builder builder() {
        return Orpheus.builder().source(rig.source()).apps(AppRules.none().allow(publisher.appRule()));
    }

    /** Starts a probe and fails fast, with the reason, when the source cannot work here. */
    private Probe start(Orpheus orpheus) throws InterruptedException {
        Probe probe = new Probe(orpheus);
        orpheus.start();
        String id = rig.source().id();
        long ready = probe.awaitThat(() -> probe.status(id).state() != SourceStatus.State.STARTING, 15_000);
        if (ready < 0 || probe.status(id).state() != SourceStatus.State.READY) {
            String reason = probe.status(id).reason();
            probe.close();
            throw new Skip("source not available: "
                    + (reason.isEmpty() ? probe.status(id).state() : reason));
        }
        return probe;
    }

    static final class Skip extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Skip(String message) {
            super(message);
        }
    }

    private void playTrack(String title, String artist, long position) throws Exception {
        publisher.track(title, artist, "Harness Album", 180_000, 0xC02859, false);
        publisher.position(position);
        publisher.state("playing");
    }

    private static boolean shows(Snapshot s, String title) {
        return s.playback().isPresent() && s.playback().track().title().equals(title);
    }

    // ---- scenarios

    private void metadata(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            playTrack(TITLE, ARTIST, 42_000);
            check(probe.await(s -> shows(s, TITLE) && s.playback().playing(), 5000) >= 0, "the track never showed up");
            Playback p = probe.orpheus.playback();
            check(
                    p.track().artists().equals(List.of(ARTIST)),
                    "artist was " + p.track().artists());
            check(
                    p.track().album().equals("Harness Album"),
                    "album was '" + p.track().album() + "'");
            check(
                    p.track().durationMillis() == 180_000,
                    "duration was " + p.track().durationMillis());
            check(
                    AppRules.matches(publisher.appRule(), p.app().id()),
                    "app id was " + p.app().id());
            check(p.state() == PlaybackState.PLAYING, "state was " + p.state());
            numbers.put("appId", p.app().id());
            numbers.put("controls", p.controls().toString());
        }
    }

    private void changeLatency(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            playTrack(TITLE, ARTIST, 0);
            check(probe.await(s -> shows(s, TITLE), 5000) >= 0, "the track never showed up");
            List<Long> samples = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                String state = i % 2 == 0 ? "paused" : "playing";
                PlaybackState want = i % 2 == 0 ? PlaybackState.PAUSED : PlaybackState.PLAYING;
                long before = System.nanoTime();
                publisher.state(state);
                long waited = probe.await(s -> s.playback().state() == want, 5000);
                check(waited >= 0, "state " + state + " never arrived");
                samples.add(System.nanoTime() - before);
            }
            samples.sort(null);
            numbers.put("medianMillis", millis(samples.get(samples.size() / 2)));
            numbers.put("maxMillis", millis(samples.get(samples.size() - 1)));
            numbers.put("note", "from before the publisher is told, so it includes the publisher's own work");
            check(
                    samples.get(samples.size() - 1) < 250 * 1_000_000L,
                    "slowest change took " + millis(samples.get(samples.size() - 1)) + " ms");
        }
    }

    private void cover(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            long before = System.nanoTime();
            playTrack(TITLE, ARTIST, 0);
            check(probe.await(s -> shows(s, TITLE) && s.cover() != null, 5000) >= 0, "no cover arrived");
            numbers.put("millisToCover", millis(System.nanoTime() - before));
            Cover cover = probe.orpheus.cover();
            numbers.put("size", cover.width() + "x" + cover.height());
            int r = cover.average() >> 16 & 0xFF;
            int g = cover.average() >> 8 & 0xFF;
            int b = cover.average() & 0xFF;
            check(
                    Math.abs(r - 0xC0) < 8 && Math.abs(g - 0x28) < 8 && Math.abs(b - 0x59) < 8,
                    "average colour was " + Integer.toHexString(cover.average()));
            publisher.track("No Cover Track", ARTIST, "", 60_000, null, false);
            check(
                    probe.await(
                                    s -> shows(s, "No Cover Track")
                                            && s.cover() == null
                                            && s.playback().coverKey() == null,
                                    5000)
                            >= 0,
                    "the old cover stayed on a track without one");
        }
    }

    private void progress(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            publisher.track(TITLE, ARTIST, "", 180_000, null, false);
            publisher.state("playing");
            long before = System.nanoTime();
            publisher.position(30_000);
            long set = before + (System.nanoTime() - before) / 2;
            check(
                    probe.await(
                                    s -> shows(s, TITLE)
                                            && s.playback().playing()
                                            && s.playback().timeline().positionMillisAt(System.nanoTime()) >= 30_000,
                                    5000)
                            >= 0,
                    "position never arrived");
            double worst = 0;
            for (int i = 0; i < 20; i++) {
                LockSupport.parkNanos(100_000_000L);
                long now = System.nanoTime();
                double expected = 30_000 + (now - set) / 1e6;
                worst = Math.max(
                        worst, Math.abs(probe.orpheus.playback().timeline().positionMillisAt(now) - expected));
            }
            numbers.put("worstErrorMillisWhilePlaying", Math.round(worst * 10) / 10.0);

            before = System.nanoTime();
            publisher.position(90_000);
            long seek = before + (System.nanoTime() - before) / 2;
            check(
                    probe.await(s -> s.playback().timeline().positionMillisAt(System.nanoTime()) >= 90_000, 5000) >= 0,
                    "the seek never arrived");
            LockSupport.parkNanos(300_000_000L);
            long now = System.nanoTime();
            double afterSeek =
                    Math.abs(probe.orpheus.playback().timeline().positionMillisAt(now) - (90_000 + (now - seek) / 1e6));
            numbers.put("errorMillisAfterSeek", Math.round(afterSeek * 10) / 10.0);

            publisher.state("paused");
            check(probe.await(s -> s.playback().state() == PlaybackState.PAUSED, 5000) >= 0, "pause never arrived");
            long frozen = probe.orpheus.playback().timeline().positionMillisAt(System.nanoTime());
            LockSupport.parkNanos(300_000_000L);
            check(
                    probe.orpheus.playback().timeline().positionMillisAt(System.nanoTime()) == frozen,
                    "the position moved while paused");
            check(worst < 100, "position error while playing was " + worst + " ms");
            check(afterSeek < 100, "position error after a seek was " + afterSeek + " ms");
        }
    }

    private record Step(
            String name,
            Control control,
            java.util.function.Function<lol.trq.orpheus.Controls, CompletableFuture<ControlResult>> send,
            String expect) {}

    private void controls(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            playTrack(TITLE, ARTIST, 0);
            check(
                    probe.await(
                                    s -> shows(s, TITLE)
                                            && s.playback().playing()
                                            && s.playback().can(Control.PAUSE),
                                    5000)
                            >= 0,
                    "the track never showed up");
            List<Step> steps = List.of(
                    new Step("pause", Control.PAUSE, c -> c.pause(), "pause"),
                    new Step("play", Control.PLAY, c -> c.play(), "play"),
                    new Step("toggle", Control.TOGGLE, c -> c.togglePlayPause(), "pause|toggle"),
                    new Step("toggle", Control.TOGGLE, c -> c.togglePlayPause(), "play|toggle"),
                    new Step("next", Control.NEXT, c -> c.next(), "next"),
                    new Step("previous", Control.PREVIOUS, c -> c.previous(), "previous"),
                    new Step("fast-forward", Control.FAST_FORWARD, c -> c.fastForward(), "fast-forward"),
                    new Step("rewind", Control.REWIND, c -> c.rewind(), "rewind"),
                    new Step("seek", Control.SEEK, c -> c.seek(Duration.ofSeconds(42)), "seek 42000"),
                    new Step("shuffle", Control.SHUFFLE, c -> c.shuffle(true), "shuffle true"),
                    new Step("repeat", Control.REPEAT, c -> c.repeat(Repeat.ALL), "repeat 2"),
                    new Step("rate", Control.RATE, c -> c.rate(1.5), "rate 1.5"),
                    new Step("volume", Control.VOLUME, c -> c.volume(0.5), "volume 0.5"),
                    new Step("stop", Control.STOP, c -> c.stop(), "stop"));
            Map<String, Object> each = new LinkedHashMap<>();
            double slowest = 0;
            for (Step step : steps) {
                if (!publisher.supportedCommands().contains(step.name)) {
                    ControlResult result =
                            step.send.apply(probe.orpheus.controls()).get(10, TimeUnit.SECONDS);
                    check(
                            result.status() == ControlStatus.UNSUPPORTED,
                            step.name + " should be unsupported here but was " + result.status());
                    each.put(step.name, "unsupported on this platform (as expected)");
                    continue;
                }
                check(
                        probe.await(s -> s.playback().can(step.control), 3000) >= 0,
                        step.name + " is not offered: "
                                + probe.orpheus.playback().controls());
                while (publisher.awaitCommand(0) != null) {
                    // drop anything left over from the previous step
                }
                long before = System.nanoTime();
                CompletableFuture<ControlResult> future = step.send.apply(probe.orpheus.controls());
                String received = publisher.awaitCommand(3000);
                long arrived = System.nanoTime() - before;
                ControlResult result = future.get(10, TimeUnit.SECONDS);
                check(result.done(), step.name + " ended " + result.status() + " " + result.reason());
                check(
                        received != null && List.of(step.expect.split("\\|")).contains(received),
                        step.name + ": the player received '" + received + "'");
                each.put(step.name + (each.containsKey(step.name) ? " (2)" : ""), millis(arrived));
                slowest = Math.max(slowest, arrived / 1e6);
                if (step.control == Control.PAUSE) {
                    check(
                            probe.await(s -> s.playback().state() == PlaybackState.PAUSED, 3000) >= 0,
                            "pause was not reflected");
                } else if (step.control == Control.PLAY) {
                    check(
                            probe.await(s -> s.playback().state() == PlaybackState.PLAYING, 3000) >= 0,
                            "play was not reflected");
                } else if (step.control == Control.SHUFFLE) {
                    check(
                            probe.await(s -> s.playback().shuffle() == lol.trq.orpheus.Shuffle.ON, 3000) >= 0,
                            "shuffle was not reflected");
                } else if (step.control == Control.REPEAT) {
                    check(probe.await(s -> s.playback().repeat() == Repeat.ALL, 3000) >= 0, "repeat was not reflected");
                } else if (step.control == Control.RATE) {
                    check(probe.await(s -> s.playback().timeline().rate() == 1.5, 3000) >= 0, "rate was not reflected");
                }
            }
            numbers.put("roundTripMillis", each);
            numbers.put("slowestMillis", Math.round(slowest * 100) / 100.0);
            check(slowest < 300, "slowest round trip was " + slowest + " ms");
        }
    }

    private void unsupportedControls(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            playTrack(TITLE, ARTIST, 0);
            check(
                    probe.await(s -> shows(s, TITLE) && s.playback().can(Control.NEXT), 5000) >= 0,
                    "the track never showed up");
            publisher.controls(false);
            check(probe.await(s -> !s.playback().can(Control.NEXT), 5000) >= 0, "a withdrawn control stayed offered");
            ControlResult next = probe.orpheus.controls().next().get(10, TimeUnit.SECONDS);
            check(next.status() == ControlStatus.UNSUPPORTED, "next was " + next.status());
            check(publisher.awaitCommand(300) == null, "an unsupported command still reached the player");
            ControlResult mute = probe.orpheus.controls().mute(true).get(10, TimeUnit.SECONDS);
            check(mute.status() == ControlStatus.UNSUPPORTED, "mute was " + mute.status());
            publisher.controls(true);
            check(probe.await(s -> s.playback().can(Control.NEXT), 5000) >= 0, "a restored control did not come back");
        }
    }

    private void rapidChanges(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            List<String> mixed = new ArrayList<>();
            probe.orpheus.listen(event -> {
                if (event instanceof PlaybackChanged) {
                    Track t = event.snapshot().playback().track();
                    if (t.title().startsWith("Rapid ") && !t.artists().isEmpty()) {
                        String index = t.title().substring(6);
                        if (!t.artistLine().equals("Artist " + index)) {
                            mixed.add(t.title() + " / " + t.artistLine());
                        }
                    }
                }
            });
            publisher.state("playing");
            int count = 40;
            for (int i = 0; i < count; i++) {
                publisher.track("Rapid " + i, "Artist " + i, "", 60_000 + i, i % 2 == 0 ? 0x102030 + i : null, false);
            }
            String last = "Rapid " + (count - 1);
            check(
                    probe.await(
                                    s -> shows(s, last)
                                            && s.playback().track().artistLine().equals("Artist " + (count - 1)),
                                    5000)
                            >= 0,
                    "the last track never became current; showing "
                            + probe.orpheus.playback().track());
            check(
                    probe.await(s -> s.playback().coverKey() == null, 3000) >= 0,
                    "a cover of an earlier track stuck to the last one");
            numbers.put(
                    "snapshotsSeen",
                    probe.events.stream()
                            .filter(e -> e instanceof PlaybackChanged)
                            .count());
            check(mixed.isEmpty(), "title and artist of different tracks were shown together: " + mixed);
        }
    }

    private void text(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            publisher.state("playing");
            Map<String, String> titles = new LinkedHashMap<>();
            titles.put("cjk", "東京の夜 — 한국어 中文歌曲");
            titles.put("rtl", "مرحبا بالعالم שלום עולם");
            titles.put("emoji", "Night 🌙 Drive 🚗💨");
            titles.put("mixed", "abc مرحبا 123 東京 (feat. Åsa & Ñoño)");
            for (Map.Entry<String, String> entry : titles.entrySet()) {
                publisher.track(entry.getValue(), entry.getValue(), entry.getValue(), 60_000, null, false);
                check(
                        probe.await(s -> shows(s, entry.getValue()), 5000) >= 0,
                        entry.getKey() + " title came back as '"
                                + probe.orpheus.playback().track().title() + "'");
                check(
                        probe.orpheus.playback().track().artistLine().equals(entry.getValue()),
                        entry.getKey() + " artist differs");
            }
            String longTitle = "Very long title " + "0123456789".repeat(300);
            publisher.track(longTitle, ARTIST, "", 60_000, null, false);
            check(
                    probe.await(s -> s.playback().track().title().startsWith("Very long title 0123456789"), 5000) >= 0,
                    "the long title never arrived");
            int length = probe.orpheus.playback().track().title().length();
            numbers.put("longTitleLength", length);
            check(length <= 1024, "a long title was not capped: " + length);
        }
    }

    private void missingMetadata(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            publisher.state("playing");
            publisher.track("Only A Title", "", "", 0, null, false);
            check(
                    probe.await(
                                    s -> shows(s, "Only A Title")
                                            && s.playback().track().durationMillis() == -1,
                                    5000)
                            >= 0,
                    "the track never showed up without a duration; showing "
                            + probe.orpheus.playback().track().durationMillis());
            Playback p = probe.orpheus.playback();
            check(p.track().artists().isEmpty(), "artists were " + p.track().artists());
            check(p.track().durationMillis() == -1, "duration was " + p.track().durationMillis());
            check(p.timeline().progressAt(System.nanoTime()) == 0f, "progress without a duration must be 0");
            check(p.coverKey() == null && probe.orpheus.cover() == null, "a cover appeared from nowhere");
        }
    }

    /** Whether this is the test player. Real players may be open at the same time. */
    private boolean mine(Playback playback) {
        return playback.isPresent()
                && AppRules.matches(publisher.appRule(), playback.app().id());
    }

    private void filtering(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(
                Orpheus.builder().source(rig.source()).apps(AppRules.defaults()).build())) {
            playTrack(TITLE, ARTIST, 0);
            check(
                    probe.awaitThat(
                                    () -> probe.orpheus.sessions().stream()
                                            .anyMatch(s -> AppRules.matches(
                                                    publisher.appRule(), s.app().id())),
                                    5000)
                            >= 0,
                    "the session is not even listed");
            LockSupport.parkNanos(300_000_000L);
            check(!mine(probe.orpheus.playback()), "an app that is not on the allow list was shown");
            check(
                    probe.orpheus.sessions().stream()
                            .noneMatch(s -> s.visible()
                                    && AppRules.matches(
                                            publisher.appRule(), s.app().id())),
                    "a hidden session is marked visible");

            probe.orpheus.apps(AppRules.defaults().allow(publisher.appRule()));
            check(probe.await(s -> shows(s, TITLE), 5000) >= 0, "allowing the app did not show it");
            probe.orpheus.apps(AppRules.all().deny(publisher.appRule()));
            check(probe.await(s -> !mine(s.playback()), 5000) >= 0, "denying the app did not hide it");

            if (rig.reportsKind()) {
                probe.orpheus.apps(AppRules.none().allow(publisher.appRule()));
                check(probe.await(s -> shows(s, TITLE), 5000) >= 0, "the app did not come back");
                publisher.track("A Video", "", "", 60_000, null, true);
                check(
                        probe.await(s -> !s.playback().isPresent(), 5000) >= 0,
                        "a video was shown without video permission");
                probe.orpheus.apps(AppRules.none().allowVideo(publisher.appRule()));
                check(probe.await(s -> shows(s, "A Video"), 5000) >= 0, "a permitted video was not shown");
                numbers.put("videoFiltering", "checked");
            } else {
                numbers.put("videoFiltering", "not possible: the platform reports no media kind");
            }
        }
    }

    private void playerCrash(Map<String, Object> numbers) throws Exception {
        try (Probe probe = start(builder().build())) {
            playTrack(TITLE, ARTIST, 0);
            check(probe.await(s -> shows(s, TITLE), 5000) >= 0, "the track never showed up");
            Playback before = probe.orpheus.playback();
            long start = System.nanoTime();
            publisher.kill();
            check(probe.await(s -> !s.playback().isPresent(), 10_000) >= 0, "a dead player stayed on screen");
            numbers.put("millisToNotice", millis(System.nanoTime() - start));
            ControlResult stale = probe.orpheus.controls(before).pause().get(10, TimeUnit.SECONDS);
            check(
                    stale.status() == ControlStatus.REFUSED || stale.status() == ControlStatus.FAILED,
                    "a command to a dead player ended " + stale.status());
            publisher = rig.publisher();
            playTrack("Back Again", ARTIST, 0);
            check(probe.await(s -> shows(s, "Back Again"), 10_000) >= 0, "a restarted player was not picked up");
        }
    }

    private void sourceSwitching(Map<String, Object> numbers) throws Exception {
        PushSource own = PushSource.create("own-player");
        Orpheus orpheus = Orpheus.builder()
                .source(rig.source())
                .source(own)
                .apps(AppRules.none().allow(publisher.appRule()))
                .build();
        try (Probe probe = start(orpheus)) {
            playTrack(TITLE, ARTIST, 0);
            check(probe.await(s -> shows(s, TITLE), 5000) >= 0, "the system session never showed up");
            own.publish(
                    PlaybackUpdate.playing(Track.of("Own Song", "Own Artist")).position(Duration.ofSeconds(5)));
            LockSupport.parkNanos(300_000_000L);
            check(shows(probe.orpheus.snapshot(), TITLE), "a second playing source stole the selection");

            long start = System.nanoTime();
            publisher.state("paused");
            check(
                    probe.await(s -> shows(s, "Own Song"), 10_000) >= 0,
                    "the selection never moved to the source that kept playing");
            double moved = (System.nanoTime() - start) / 1e6;
            numbers.put("millisUntilSwitch", Math.round(moved));
            check(moved > 2500, "the selection flipped after only " + moved + " ms");

            probe.orpheus.pin(rig.source().id());
            check(probe.await(s -> shows(s, TITLE), 5000) >= 0, "pinning the system source did not select it");
            probe.orpheus.unpin();
            check(probe.await(s -> shows(s, "Own Song"), 5000) >= 0, "unpinning did not return to the playing source");
            own.clear();
            check(
                    probe.await(s -> shows(s, TITLE), 5000) >= 0,
                    "the paused session was not shown when it was the only one left");
        }
    }

    private void lyrics(Map<String, Object> numbers) throws Exception {
        Path dir = Files.createTempDirectory("orpheus-harness-lyrics");
        try {
            StringBuilder lrc = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                lrc.append(String.format("[00:%02d.00]generated line %d%n", i * 2, i));
            }
            Files.writeString(dir.resolve(ARTIST + " - Lyric Song.lrc"), lrc.toString());
            Files.writeString(dir.resolve("Somebody Else - Lyric Song.lrc"), "[00:00.00]wrong artist\n");
            Orpheus orpheus = builder().lyrics(LocalLyrics.in(dir)).build();
            try (Probe probe = start(orpheus)) {
                publisher.track("Lyric Song", ARTIST, "", 180_000, null, false);
                publisher.state("playing");
                long before = System.nanoTime();
                publisher.position(0);
                long zero = before + (System.nanoTime() - before) / 2;
                check(probe.await(s -> s.lyrics().kind() == Lyrics.Kind.LINE, 5000) >= 0, "no lyrics were found");
                check(
                        probe.orpheus.lyrics().line(0).text().equals("generated line 0"),
                        "lyrics of the wrong artist were chosen");
                double worst = 0;
                for (int line = 2; line <= 5; line++) {
                    int want = line;
                    while (System.nanoTime() - zero < (want * 2000L - 150) * 1_000_000L) {
                        LockSupport.parkNanos(1_000_000L);
                    }
                    long deadline = System.nanoTime() + SECOND;
                    while (true) {
                        Snapshot s = probe.orpheus.snapshot();
                        long now = System.nanoTime();
                        if (s.lyrics().lineAt(s.playback().timeline().positionMillisAt(now)) >= want) {
                            worst = Math.max(worst, Math.abs((now - zero) / 1e6 - want * 2000.0));
                            break;
                        }
                        check(now < deadline, "line " + want + " never became current");
                        Thread.onSpinWait();
                    }
                }
                numbers.put("worstLineChangeErrorMillis", Math.round(worst * 10) / 10.0);

                publisher.track("Unknown Song", ARTIST, "", 180_000, null, false);
                check(
                        probe.await(s -> shows(s, "Unknown Song") && s.lyrics() == Lyrics.NONE, 5000) >= 0,
                        "lyrics of the previous song stayed");
                check(worst < 50, "a lyric line changed " + worst + " ms off");
            }
        } finally {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    private void startStop(Map<String, Object> numbers) throws Exception {
        playTrack(TITLE, ARTIST, 0);
        Orpheus orpheus = builder().build();
        for (int i = 0; i < 100; i++) {
            try (Probe probe = start(orpheus)) {
                check(probe.await(s -> shows(s, TITLE), 5000) >= 0, "warm-up cycle " + i + " saw no session");
            }
        }
        System.gc();
        Map<String, Object> before = ProcessStats.sample();
        int cycles = 100;
        long start = System.nanoTime();
        for (int i = 0; i < cycles; i++) {
            try (Probe probe = start(orpheus)) {
                check(probe.await(s -> shows(s, TITLE), 5000) >= 0, "cycle " + i + " saw no session");
            }
        }
        numbers.put("millisPerCycle", millis((System.nanoTime() - start) / cycles));
        System.gc();
        Map<String, Object> after = ProcessStats.sample();
        numbers.put("before", before);
        numbers.put("after", after);
        long threadsBefore = ((Number) before.get("libraryThreads")).longValue();
        long threadsAfter = ((Number) after.get("libraryThreads")).longValue();
        check(threadsAfter <= threadsBefore, "library threads grew from " + threadsBefore + " to " + threadsAfter);
        for (String key : List.of("osThreads", "handles", "children")) {
            if (before.get(key) instanceof Number b && after.get(key) instanceof Number a) {
                check(
                        a.longValue() - b.longValue() <= 4,
                        key + " grew from " + b + " to " + a + " over " + cycles + " cycles");
            }
        }
    }
}
