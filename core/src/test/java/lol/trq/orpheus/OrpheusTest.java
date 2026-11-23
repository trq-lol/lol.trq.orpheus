/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import static lol.trq.orpheus.testkit.FakeSource.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import lol.trq.orpheus.event.OrpheusEvent;
import lol.trq.orpheus.event.PlaybackChanged;
import lol.trq.orpheus.event.SourceStatusChanged;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.PlaybackUpdate;
import lol.trq.orpheus.source.PushSource;
import lol.trq.orpheus.source.QueueAccess;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.testkit.FakeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OrpheusTest {
    private Orpheus orpheus;

    @AfterEach
    void stop() {
        if (orpheus != null) {
            orpheus.stop();
        }
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    /** Waits for an event instead of sleeping. */
    private static CountDownLatch when(Orpheus orpheus, Predicate<OrpheusEvent> condition) {
        CountDownLatch latch = new CountDownLatch(1);
        orpheus.listen(event -> {
            if (condition.test(event)) {
                latch.countDown();
            }
        });
        return latch;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out waiting for an event");
    }

    private static long libraryThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("orpheus-"))
                .count();
    }

    @Test
    void everythingAnswersWithoutThrowingBeforeStartAndWithNothingPlaying() throws Exception {
        FakeSource source = new FakeSource("fake", false);
        orpheus = Orpheus.builder().source(source).build();
        assertEquals(ControlStatus.UNSUPPORTED, get(orpheus.controls().pause()).status());
        assertFalse(orpheus.playback().isPresent());
        assertEquals(0, libraryThreads(), "nothing may run before start");

        orpheus.start();
        assertEquals(
                ControlStatus.UNSUPPORTED,
                get(orpheus.controls().seek(Duration.ofSeconds(5))).status());
        assertEquals(ControlStatus.UNSUPPORTED, get(orpheus.queue().add("x")).status());
        assertFalse(get(orpheus.devices().list()).done());
        assertTrue(get(orpheus.library().search("q", 5)).value().isEmpty());
    }

    @Test
    void commandsReachTheSourceWithTheirArguments() throws Exception {
        FakeSource source = new FakeSource("fake", false);
        orpheus = Orpheus.builder().source(source).build();
        CountDownLatch playing = when(orpheus, e -> e instanceof PlaybackChanged);
        orpheus.start();
        get(orpheus.controls().pause());
        source.report(
                session("s1").can(Control.SEEK, Control.PLAY, Control.PAUSE).build());
        await(playing);

        assertTrue(get(orpheus.controls().seek(Duration.ofSeconds(42))).done());
        ControlRequest seek = source.requests.get(0);
        assertEquals("s1", seek.sessionId());
        assertEquals(42_000, seek.positionMillis());

        assertTrue(get(orpheus.controls().togglePlayPause()).done());
        assertEquals(Control.PAUSE, source.requests.get(1).control(), "toggle falls back to pause while playing");
        assertEquals(ControlStatus.UNSUPPORTED, get(orpheus.controls().next()).status());
    }

    @Test
    void aSilentPlayerTimesOutAndStopFailsWhatIsStillInFlight() throws Exception {
        FakeSource source = new FakeSource("fake", false);
        source.answer = new CompletableFuture<>();
        orpheus = Orpheus.builder()
                .source(source)
                .commandTimeout(Duration.ofMillis(50))
                .build();
        CountDownLatch playing = when(orpheus, e -> e instanceof PlaybackChanged);
        orpheus.start();
        get(orpheus.controls().pause());
        source.report(session("s1").can(Control.PAUSE).build());
        await(playing);
        ControlResult timedOut = get(orpheus.controls().pause());
        assertEquals(ControlStatus.FAILED, timedOut.status());
        assertEquals("timed out", timedOut.reason());

        orpheus.stop();
        orpheus = Orpheus.builder()
                .source(source)
                .commandTimeout(Duration.ofMinutes(5))
                .build();
        CountDownLatch again = when(orpheus, e -> e instanceof PlaybackChanged);
        orpheus.start();
        get(orpheus.controls().pause());
        source.report(session("s1").can(Control.PAUSE).build());
        await(again);
        CompletableFuture<ControlResult> inFlight = orpheus.controls().pause();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (source.requests.size() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        source.stopped = false;
        orpheus.stop();
        assertEquals(ControlStatus.FAILED, get(inFlight).status());
        assertTrue(source.stopped);
    }

    @Test
    void aCommandForASessionThatLeftIsRefused() throws Exception {
        FakeSource source = new FakeSource("fake", false);
        orpheus = Orpheus.builder().source(source).build();
        CountDownLatch playing = when(
                orpheus,
                e -> e instanceof PlaybackChanged && e.snapshot().playback().isPresent());
        CountDownLatch gone = when(
                orpheus,
                e -> e instanceof PlaybackChanged && !e.snapshot().playback().isPresent());
        orpheus.start();
        get(orpheus.controls().pause());
        source.report(session("s1").can(Control.PAUSE).build());
        await(playing);
        Controls bound = orpheus.controls(orpheus.playback());
        source.report();
        await(gone);
        assertEquals(ControlStatus.REFUSED, get(bound.pause()).status());
    }

    @Test
    void aSourceThatThrowsOnStartBecomesUnavailableAndOthersKeepWorking() throws Exception {
        FakeSource broken = new FakeSource("broken", false);
        broken.failStart = true;
        PushSource push = PushSource.create("mine");
        orpheus = Orpheus.builder().source(broken).source(push).build();
        List<SourceStatus> statuses = new CopyOnWriteArrayList<>();
        CountDownLatch unavailable = when(orpheus, e -> {
            if (e instanceof SourceStatusChanged s && s.sourceId().equals("broken")) {
                statuses.add(s.status());
                return true;
            }
            return false;
        });
        CountDownLatch playing = when(orpheus, e -> e instanceof PlaybackChanged);
        orpheus.start();
        push.publish(PlaybackUpdate.playing(Track.of("Mine", "Me")).position(Duration.ofSeconds(3)));
        await(unavailable);
        await(playing);
        assertEquals(SourceStatus.State.UNAVAILABLE, statuses.get(0).state());
        assertEquals("Mine", orpheus.playback().track().title());
    }

    @Test
    void aListenerThatThrowsDoesNotStopDelivery() throws Exception {
        PushSource push = PushSource.create("mine");
        orpheus = Orpheus.builder().source(push).build();
        orpheus.listen(event -> {
            throw new IllegalStateException("bad listener");
        });
        CountDownLatch second = when(
                orpheus,
                e -> e instanceof PlaybackChanged
                        && e.snapshot().playback().track().title().equals("Two"));
        orpheus.start();
        push.publish(PlaybackUpdate.playing(Track.of("One", "Me")));
        push.publish(PlaybackUpdate.playing(Track.of("Two", "Me")));
        await(second);
    }

    @Test
    void stopFromAListenerDoesNotDeadlockAndLeavesNoThreads() throws Exception {
        PushSource push = PushSource.create("mine");
        orpheus = Orpheus.builder().source(push).build();
        CountDownLatch stopped = new CountDownLatch(1);
        orpheus.listen(event -> {
            if (event instanceof PlaybackChanged) {
                orpheus.stop();
                stopped.countDown();
            }
        });
        orpheus.start();
        push.publish(PlaybackUpdate.playing(Track.of("One", "Me")));
        await(stopped);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (libraryThreads() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(0, libraryThreads());
        assertFalse(orpheus.playback().isPresent());
    }

    @Test
    void aHundredStartStopCyclesLeakNoThreads() throws Exception {
        PushSource push = PushSource.create("mine");
        orpheus = Orpheus.builder().source(push).build();
        push.publish(PlaybackUpdate.playing(Track.of("One", "Me")));
        for (int i = 0; i < 100; i++) {
            CountDownLatch playing = when(orpheus, e -> e instanceof PlaybackChanged);
            orpheus.start();
            await(playing);
            orpheus.stop();
        }
        assertEquals(0, libraryThreads());
    }

    @Test
    void stopAndStartAgainFromAListenerKeepsWorking() throws Exception {
        PushSource push = PushSource.create("mine");
        orpheus = Orpheus.builder().source(push).build();
        CountDownLatch restarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        orpheus.listen(event -> {
            if (event instanceof PlaybackChanged && once.compareAndSet(false, true)) {
                orpheus.stop();
                orpheus.start();
                restarted.countDown();
            }
        });
        orpheus.start();
        push.publish(PlaybackUpdate.playing(Track.of("One", "Me")));
        await(restarted);
        CountDownLatch second = when(
                orpheus,
                e -> e instanceof PlaybackChanged
                        && e.snapshot().playback().track().title().equals("Two"));
        get(orpheus.controls().pause());
        push.publish(PlaybackUpdate.playing(Track.of("Two", "Me")));
        await(second);
        assertEquals("Two", orpheus.playback().track().title(), "the restarted hub must be the one that is live");
    }

    @Test
    void localLyricsStillWorkAfterAStopAndANewStart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.writeString(dir.resolve("Me - One.lrc"), "[00:01.00]a line");
        PushSource push = PushSource.create("mine");
        orpheus = Orpheus.builder()
                .source(push)
                .lyrics(lol.trq.orpheus.lyrics.LocalLyrics.in(dir))
                .build();
        push.publish(PlaybackUpdate.playing(Track.of("One", "Me")));
        for (int round = 0; round < 2; round++) {
            CountDownLatch found = when(
                    orpheus,
                    e -> e instanceof lol.trq.orpheus.event.LyricsChanged
                            && e.snapshot().lyrics().size() == 1);
            orpheus.start();
            await(found);
            orpheus.stop();
        }
    }

    @Test
    void pushHandlersAdvertiseControlsAndReportFailures() throws Exception {
        PushSource push = PushSource.create("mine");
        List<Long> seeks = new CopyOnWriteArrayList<>();
        push.handler(Control.SEEK, request -> seeks.add(request.positionMillis()));
        push.handler(Control.NEXT, request -> {
            throw new IllegalStateException("end of queue");
        });
        orpheus = Orpheus.builder().source(push).apps(AppRules.none()).build();
        CountDownLatch playing = when(orpheus, e -> e instanceof PlaybackChanged);
        orpheus.start();
        push.publish(PlaybackUpdate.playing(Track.of("One", "Me")));
        await(playing);
        assertTrue(orpheus.playback().can(Control.SEEK), "a push source is visible even when no app is allowed");
        assertTrue(get(orpheus.controls().seek(Duration.ofSeconds(7))).done());
        assertEquals(List.of(7000L), seeks);
        ControlResult next = get(orpheus.controls().next());
        assertEquals(ControlStatus.FAILED, next.status());
        assertEquals("end of queue", next.reason());
        assertEquals(ControlStatus.UNSUPPORTED, get(orpheus.controls().stop()).status());
        assertEquals(ControlStatus.UNSUPPORTED, get(orpheus.queue().remove("x")).status());
        CountDownLatch offered = when(orpheus, e -> e.snapshot().playback().can(Control.PREVIOUS));
        push.responder(
                Control.PREVIOUS, request -> CompletableFuture.completedFuture(ControlResult.refused("first track")));
        await(offered);
        ControlResult previous = get(orpheus.controls().previous());
        assertEquals(ControlStatus.REFUSED, previous.status(), "a player must be able to say no");
        assertEquals("first track", previous.reason());

        push.provide(QueueAccess.class, new QueueAccess() {
            @Override
            public CompletableFuture<lol.trq.orpheus.source.Reply<List<lol.trq.orpheus.source.QueueItem>>> queue(
                    String s) {
                return CompletableFuture.completedFuture(lol.trq.orpheus.source.Reply.of(
                        List.of(new lol.trq.orpheus.source.QueueItem("q1", Track.of("Next", "Me")))));
            }

            @Override
            public CompletableFuture<ControlResult> move(String s, String item, String after) {
                throw new IllegalStateException("boom");
            }

            @Override
            public CompletableFuture<ControlResult> add(String s, String uri) {
                return null;
            }

            @Override
            public CompletableFuture<ControlResult> remove(String s, String item) {
                return CompletableFuture.completedFuture(ControlResult.DONE);
            }
        });
        assertEquals("q1", get(orpheus.queue().items()).value().get(0).id());
        assertEquals(
                ControlStatus.FAILED,
                get(orpheus.queue().move("a", null)).status(),
                "a throwing source is a failure, not an exception");
        assertEquals(ControlStatus.FAILED, get(orpheus.queue().add("x")).status());
    }
}
