/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.spotify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.ControlStatus;
import lol.trq.orpheus.MediaKind;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.SourceApp;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpotifySourceTest {
    private static final String PLAYING = """
            {"device":{"id":"abc","is_active":true,"is_restricted":false,"supports_volume":true,"volume_percent":40},
             "shuffle_state":true,"repeat_state":"context","progress_ms":12000,"is_playing":true,
             "item":{"id":"T1","uri":"spotify:track:T1","type":"track","name":"First Light","duration_ms":200000,
               "track_number":3,"artists":[{"name":"Ada"},{"name":"Bo"}],
               "album":{"name":"Dawn","artists":[{"name":"Ada"}],"images":[
                 {"url":"https://i.scdn.co/image/large","width":640},
                 {"url":"https://i.scdn.co/image/medium","width":300},
                 {"url":"https://i.scdn.co/image/small","width":64}]}},
             "actions":{"disallows":{"resuming":true,"skipping_prev":true}}}
            """;

    private final Server server = new Server();
    private final Sink sink = new Sink();
    private final List<String> rejected = new CopyOnWriteArrayList<>();
    private volatile String token = "token-1";
    private final SpotifySource source = SpotifySource.create(
            new TokenSource() {
                @Override
                public CompletableFuture<String> accessToken() {
                    return CompletableFuture.completedFuture(token);
                }

                @Override
                public void rejected(String old) {
                    rejected.add(old);
                }
            },
            server);

    @AfterEach
    void stop() {
        source.stop();
        assertEquals(
                0,
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().startsWith("orpheus-spotify"))
                        .count(),
                "no thread may survive stop");
    }

    @Test
    void readsEveryFieldOfWhatIsPlaying() throws Exception {
        server.player = answer(200, PLAYING);
        source.start(sink);
        SessionUpdate session = sink.session();
        assertEquals("First Light", session.track().title());
        assertEquals("Ada, Bo", session.track().artistLine());
        assertEquals("Dawn", session.track().album());
        assertEquals(200_000, session.track().durationMillis());
        assertEquals("T1", session.track().id());
        assertEquals(12_000, session.positionMillis());
        assertEquals(PlaybackState.PLAYING, session.state());
        assertEquals(Shuffle.ON, session.shuffle());
        assertEquals(Repeat.ALL, session.repeat());
        assertEquals(0.4, session.volume(), 1e-9);
        assertTrue(session.controls().contains(Control.PAUSE));
        assertTrue(session.controls().contains(Control.PLAY), "resuming is always disallowed while playing");
        assertFalse(session.controls().contains(Control.PREVIOUS));
        assertNull(session.cover(), "the text must not wait for the cover");

        SessionUpdate second = sink.session();
        assertEquals("https://i.scdn.co/image/medium", second.cover().key());
        assertEquals("Spotify", source.display().attribution());
        assertTrue(source.display().artworkUnmodified());
        assertEquals("https://open.spotify.com/track/T1", source.display().linkBack());
        assertEquals(SourceStatus.READY, sink.status("READY"));
    }

    @Test
    void theTokenIsNeverSentToTheImageHost() throws Exception {
        server.player = answer(200, PLAYING);
        source.start(sink);
        sink.session();
        sink.session();
        for (Request request : server.requests) {
            boolean api = request.url.startsWith("https://api.spotify.com/v1/");
            assertEquals(api, request.headers.containsKey("Authorization"), request.url);
            assertTrue(api || request.url.startsWith("https://i.scdn.co/"), request.url);
        }
    }

    @Test
    void adsLocalFilesEpisodesAndOddAnswersDoNotBreakIt() {
        assertNull(parse("{\"is_playing\":true,\"item\":null,\"currently_playing_type\":\"ad\"}"));
        assertNull(parse("{\"is_playing\":false,\"item\":null}"));
        assertNull(parse("{}"));

        SessionUpdate local = parse("{\"is_playing\":true,\"item\":{\"id\":null,\"is_local\":true,"
                + "\"uri\":\"spotify:local:a:b:c:10\",\"name\":\"Mine\",\"artists\":[{\"name\":\"\"}],\"album\":{}}}");
        assertNull(local.track().id());
        assertEquals("Mine", local.track().title());
        assertEquals(-1, local.track().durationMillis());
        assertNull(local.coverUri());

        SessionUpdate episode = parse(
                "{\"is_playing\":true,\"item\":{\"id\":\"E\",\"type\":\"episode\",\"name\":\"Ep\","
                        + "\"show\":{\"name\":\"Show\"},\"images\":[{\"url\":\"https://i.scdn.co/e\",\"width\":640}]}}");
        assertEquals("Show", episode.track().artistLine());
        assertEquals(MediaKind.OTHER, episode.track().kind());
        assertEquals("https://i.scdn.co/e", episode.coverUri());

        SessionUpdate odd = parse("{\"is_playing\":true,\"progress_ms\":\"x\",\"device\":[],\"repeat_state\":7,"
                + "\"shuffle_state\":null,\"item\":{\"name\":\"n\",\"artists\":{},\"album\":[]},\"actions\":1}");
        assertTrue(Double.isNaN(odd.volume()), "an unknown volume is NaN, so another source can fill it in");
        assertEquals(0, odd.positionMillis());
        assertEquals(Shuffle.UNKNOWN, odd.shuffle());
        assertEquals(Repeat.UNKNOWN, odd.repeat());

        SessionUpdate restricted =
                parse("{\"is_playing\":true,\"device\":{\"is_restricted\":true},\"item\":{\"name\":\"a\"}}");
        assertTrue(restricted.controls().isEmpty());
    }

    @Test
    void onlyTheServicesOwnImageHostsAreUsed() {
        assertTrue(SpotifySource.cdn("https://i.scdn.co/image/x"));
        assertTrue(SpotifySource.cdn("https://mosaic.spotifycdn.com/x"));
        for (String bad : List.of(
                "http://i.scdn.co/x",
                "https://i.scdn.co.evil.test/x",
                "https://evilscdn.co/x",
                "https://user@i.scdn.co/x",
                "https://i.scdn.co:8443/x",
                "file:///etc/passwd",
                "https://127.0.0.1/x",
                "not a url",
                "")) {
            assertFalse(SpotifySource.cdn(bad), bad);
        }
    }

    @Test
    void withoutATokenNothingIsRequested() throws Exception {
        token = null;
        source.start(sink);
        assertEquals("not signed in", sink.status("UNAVAILABLE").reason());
        assertEquals(0, server.requests.size());

        token = "token-1";
        server.player = answer(200, PLAYING);
        source.refresh();
        assertEquals("First Light", sink.session().track().title());
    }

    @Test
    void aRejectedTokenIsReplacedOnceAndNotLooped() throws Exception {
        server.player = answer(401, "{}");
        source.start(sink);
        assertEquals("not signed in", sink.status("UNAVAILABLE").reason());
        Thread.sleep(1500);
        assertEquals(List.of("token-1"), rejected);
        assertEquals(1, server.requests.size());
    }

    @Test
    void likingChecksThatTheTrackIsStillTheOneShown() throws Exception {
        server.player = answer(200, PLAYING);
        source.start(sink);
        sink.session();
        assertTrue(source.like("spotify", true).get(5, TimeUnit.SECONDS).done());
        assertTrue(server.requests.stream()
                .anyMatch(r -> r.method.equals("PUT") && r.url.endsWith("/me/library?uris=spotify%3Atrack%3AT1")));
        server.requests.clear();
        server.player = answer(200, PLAYING.replace("spotify:track:T1", "spotify:track:T2"));
        assertEquals(
                "the track changed",
                source.like("spotify", true).get(5, TimeUnit.SECONDS).reason());
        assertTrue(server.requests.stream().allMatch(r -> r.method.equals("GET")));
    }

    @Test
    void aVolumeDragSendsTheNewestValueAndOldCommandsExpire() throws Exception {
        server.player = answer(204, "");
        source.start(sink);
        sink.status("READY");
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.onRequest = r -> {
            if (r.url.endsWith("/next")) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return answer(204, "");
        };
        source.send(ControlRequest.of("spotify", Control.NEXT));
        List<CompletableFuture<ControlResult>> drag = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            drag.add(source.send(new ControlRequest("spotify", Control.VOLUME, 0, i / 10.0, false, Repeat.OFF)));
        }
        release.countDown();
        assertTrue(drag.get(9).get(5, TimeUnit.SECONDS).done());
        assertFalse(drag.get(0).get(5, TimeUnit.SECONDS).done());
        assertEquals(
                List.of("https://api.spotify.com/v1/me/player/volume?volume_percent=100"),
                server.requests.stream()
                        .map(Request::url)
                        .filter(u -> u.contains("volume"))
                        .toList());

        java.util.concurrent.CountDownLatch slow = new java.util.concurrent.CountDownLatch(1);
        server.onRequest = r -> {
            if (r.url.endsWith("/next")) {
                try {
                    slow.await(4500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return answer(204, "");
        };
        source.send(ControlRequest.of("spotify", Control.NEXT));
        CompletableFuture<ControlResult> late = source.send(ControlRequest.of("spotify", Control.PAUSE));
        assertFalse(late.get(8, TimeUnit.SECONDS).done());
        assertTrue(server.requests.stream().noneMatch(r -> r.url.endsWith("/pause")), "a stale command was sent");
    }

    @Test
    void aReplacementTokenIsUsedAtOnce() throws Exception {
        server.onRequest = request -> {
            if (request.headers.getOrDefault("Authorization", "").equals("Bearer token-1")) {
                token = "token-2";
                return answer(401, "{}");
            }
            return answer(200, PLAYING);
        };
        source.start(sink);
        assertEquals("First Light", sink.session().track().title());
        assertEquals(List.of("token-1"), rejected);
    }

    @Test
    void aTokenWithLineBreaksIsRefusedBeforeItReachesAHeader() throws Exception {
        token = "a\r\nX-Evil: 1";
        source.start(sink);
        sink.status("UNAVAILABLE");
        assertEquals(0, server.requests.size());
    }

    @Test
    void aRateLimitStopsPollsAndCommands() throws Exception {
        server.player = new HttpCall.Answer(429, 600, new byte[0]);
        source.start(sink);
        assertEquals("rate limited", sink.status("UNAVAILABLE").reason());
        source.refresh();
        source.refresh();
        ControlResult result =
                source.send(ControlRequest.of("spotify", Control.PAUSE)).get(5, TimeUnit.SECONDS);
        assertFalse(result.done());
        Thread.sleep(2000);
        assertEquals(1, server.requests.size(), "nothing may be sent before the limit ends");
    }

    @Test
    void aShortRateLimitKeepsWhatIsShownAndPollsAgainAfterIt() throws Exception {
        List<HttpCall.Answer> answers =
                new CopyOnWriteArrayList<>(List.of(new HttpCall.Answer(429, 1, new byte[0]), answer(200, PLAYING)));
        server.onRequest = request ->
                request.url.contains("/me/player") && answers.size() > 1 ? answers.remove(0) : answer(200, PLAYING);
        long start = System.nanoTime();
        source.start(sink);
        assertEquals("First Light", sink.session().track().title());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 950, "polled inside the limit");
    }

    @Test
    void commandsUseTheDocumentedRequests() throws Exception {
        server.player = answer(204, "");
        source.start(sink);
        sink.status("READY");
        server.requests.clear();
        assertTrue(send(Control.PLAY, 0, 0, false, Repeat.OFF).done());
        assertTrue(send(Control.SEEK, 42_000, 0, false, Repeat.OFF).done());
        assertTrue(send(Control.SHUFFLE, 0, 0, true, Repeat.OFF).done());
        assertTrue(send(Control.REPEAT, 0, 0, false, Repeat.ONE).done());
        assertTrue(send(Control.VOLUME, 0, 0.255, false, Repeat.OFF).done());
        assertTrue(send(Control.NEXT, 0, 0, false, Repeat.OFF).done());
        assertEquals(
                ControlStatus.UNSUPPORTED,
                send(Control.RATE, 0, 2, false, Repeat.OFF).status());
        assertTrue(source.play("spotify", "spotify:album:AA")
                .get(5, TimeUnit.SECONDS)
                .done());
        assertTrue(source.add("spotify", "spotify:track:BB")
                .get(5, TimeUnit.SECONDS)
                .done());
        assertTrue(source.transfer("spotify", "dev1").get(5, TimeUnit.SECONDS).done());
        List<String> sent = new ArrayList<>();
        for (Request r : server.requests) {
            if (!r.method.equals("GET")) {
                sent.add(r.method + " " + r.url.substring("https://api.spotify.com/v1".length())
                        + (r.body == null ? "" : " " + r.body));
            }
        }
        assertEquals(
                List.of(
                        "PUT /me/player/play",
                        "PUT /me/player/seek?position_ms=42000",
                        "PUT /me/player/shuffle?state=true",
                        "PUT /me/player/repeat?state=track",
                        "PUT /me/player/volume?volume_percent=26",
                        "POST /me/player/next",
                        "PUT /me/player/play {\"context_uri\":\"spotify:album:AA\"}",
                        "POST /me/player/queue?uri=spotify%3Atrack%3ABB",
                        "PUT /me/player {\"device_ids\":[\"dev1\"]}"),
                sent);
    }

    @Test
    void urisAndDeviceIdsCannotCarryAnythingElse() throws Exception {
        server.player = answer(204, "");
        source.start(sink);
        sink.status("READY");
        server.requests.clear();
        for (String bad : List.of("spotify:track:a\"}", "https://x", "spotify:track:", "spotify:user:me", "")) {
            assertEquals(
                    ControlStatus.REFUSED,
                    source.play("s", bad).get(5, TimeUnit.SECONDS).status(),
                    bad);
            assertEquals(
                    ControlStatus.REFUSED,
                    source.add("s", bad).get(5, TimeUnit.SECONDS).status(),
                    bad);
        }
        assertEquals(
                ControlStatus.REFUSED,
                source.transfer("s", "a\"]}").get(5, TimeUnit.SECONDS).status());
        assertEquals(
                ControlStatus.REFUSED,
                source.like("s", true).get(5, TimeUnit.SECONDS).status());
        assertEquals(0, server.requests.size());
    }

    @Test
    void refusalsSayWhy() {
        assertEquals(
                ControlResult.refused("needs Spotify Premium"),
                SpotifySource.outcome(answer(403, "{\"error\":{\"status\":403,\"reason\":\"PREMIUM_REQUIRED\"}}")));
        assertEquals(
                ControlResult.refused("NO_ACTIVE_DEVICE"),
                SpotifySource.outcome(answer(404, "{\"error\":{\"reason\":\"NO_ACTIVE_DEVICE\"}}")));
        assertEquals("QUOTA_EXCEEDED", SpotifySource.reason(answer(429, "{\"reason\":\"QUOTA_EXCEEDED\"}")));
        assertEquals("", SpotifySource.reason(answer(500, "<html>")));
        assertEquals(
                ControlStatus.FAILED, SpotifySource.outcome(answer(502, "")).status());
    }

    @Test
    void oneFailureKeepsTheSessionAndRepeatedOnesClearIt() throws Exception {
        server.player = answer(200, PLAYING);
        source.start(sink);
        sink.session();
        sink.session();
        server.player = answer(500, "");
        source.refresh();
        Thread.sleep(2500);
        assertTrue(sink.lists.isEmpty(), "one failure must not blank the display");
        assertEquals("HTTP 500", sink.status("UNAVAILABLE").reason());
        assertEquals(List.of(), sink.lists.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void anotherSourcesSpotifySessionTriggersAPollButOthersDoNot() throws Exception {
        server.player = answer(204, "");
        source.start(sink);
        sink.status("READY");
        int before = server.requests.size();
        source.changedElsewhere(new SourceApp("vlc.exe", "VLC"));
        Thread.sleep(1800);
        assertEquals(before, server.requests.size());
        for (int i = 0; i < 20; i++) {
            source.changedElsewhere(new SourceApp("Spotify.exe", "Spotify"));
        }
        Thread.sleep(600);
        assertEquals(before + 1, server.requests.size(), "a burst is one poll");
    }

    @Test
    void readsTheQueueDevicesPlaylistsAndSearch() throws Exception {
        server.onRequest = r -> r.url.contains("/queue")
                ? answer(200, "{\"queue\":[{\"id\":\"A\",\"uri\":\"spotify:track:A\",\"name\":\"a\"},null,{}]}")
                : r.url.contains("/devices")
                        ? answer(
                                200,
                                "{\"devices\":[{\"id\":\"d\",\"name\":\"Desk\",\"type\":\"Computer\",\"is_active\":true},{\"id\":null}]}")
                        : r.url.contains("/playlists")
                                ? answer(
                                        200,
                                        "{\"items\":[null,{\"uri\":\"spotify:playlist:P\",\"name\":\"p\",\"items\":{\"total\":4}},{\"uri\":\"spotify:playlist:Q\",\"name\":\"q\",\"tracks\":{\"total\":5}}]}")
                                : r.url.contains("/search")
                                        ? answer(200, "{\"tracks\":{\"items\":[{\"id\":\"S\",\"name\":\"s\"}]}}")
                                        : answer(204, "");
        source.start(sink);
        assertEquals(
                "0:spotify:track:A",
                source.queue("s").get(5, TimeUnit.SECONDS).value().get(0).id());
        assertEquals(1, source.queue("s").get(5, TimeUnit.SECONDS).value().size());
        assertEquals(
                "computer",
                source.devices("s").get(5, TimeUnit.SECONDS).value().get(0).type());
        assertEquals(
                List.of(4, 5),
                source.playlists().get(5, TimeUnit.SECONDS).value().stream()
                        .map(p -> p.size())
                        .toList());
        assertEquals(
                "s",
                source.search("a b&c", 99)
                        .get(5, TimeUnit.SECONDS)
                        .value()
                        .get(0)
                        .title());
        assertTrue(server.requests.stream().anyMatch(r -> r.url.endsWith("limit=10&q=a+b%26c")));
    }

    @Test
    void commandsAnswerBeforeStartAndAfterStop() throws Exception {
        assertEquals(
                ControlStatus.FAILED,
                source.send(ControlRequest.of("s", Control.PLAY))
                        .get(1, TimeUnit.SECONDS)
                        .status());
        server.player = answer(204, "");
        source.start(sink);
        sink.status("READY");
        source.stop();
        assertEquals(
                ControlStatus.FAILED,
                source.send(ControlRequest.of("s", Control.PLAY))
                        .get(1, TimeUnit.SECONDS)
                        .status());
        source.start(sink);
        sink.status("READY");
    }

    private ControlResult send(Control control, long position, double value, boolean on, Repeat repeat)
            throws Exception {
        return source.send(new ControlRequest("spotify", control, position, value, on, repeat))
                .get(5, TimeUnit.SECONDS);
    }

    private static SessionUpdate parse(String json) {
        return SpotifySource.session((Map<?, ?>) lol.trq.orpheus.internal.MiniJson.parse(json), 0, null);
    }

    private static HttpCall.Answer answer(int status, String body) {
        return new HttpCall.Answer(status, -1, body.getBytes(StandardCharsets.UTF_8));
    }

    private record Request(String method, String url, Map<String, String> headers, String body) {}

    private interface Reaction {
        HttpCall.Answer to(Request request);
    }

    private static final class Server implements HttpCall {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        volatile Answer player = answer(204, "");
        volatile Reaction onRequest;

        @Override
        public Answer send(String method, String url, Map<String, String> headers, byte[] body, int maxBytes)
                throws IOException {
            Request request = new Request(
                    method, url, Map.copyOf(headers), body == null ? null : new String(body, StandardCharsets.UTF_8));
            requests.add(request);
            if (url.startsWith("https://i.scdn.co/")) {
                return new Answer(200, -1, new byte[] {1, 2, 3});
            }
            if (onRequest != null) {
                return onRequest.to(request);
            }
            return method.equals("GET") ? player : answer(204, "");
        }
    }

    private static final class Sink implements SourceSink {
        final BlockingQueue<SourceStatus> statuses = new LinkedBlockingQueue<>();
        final BlockingQueue<List<SessionUpdate>> lists = new LinkedBlockingQueue<>();

        @Override
        public void status(SourceStatus status) {
            statuses.add(status);
        }

        @Override
        public void sessions(List<SessionUpdate> sessions) {
            lists.add(sessions);
        }

        SessionUpdate session() throws InterruptedException {
            List<SessionUpdate> list;
            do {
                list = lists.poll(10, TimeUnit.SECONDS);
            } while (list != null && list.isEmpty());
            assertTrue(list != null, "no session was reported");
            return list.get(0);
        }

        SourceStatus status(String state) throws InterruptedException {
            SourceStatus status;
            do {
                status = statuses.poll(15, TimeUnit.SECONDS);
            } while (status != null && !status.state().name().equals(state));
            assertTrue(status != null, "never became " + state);
            return status;
        }
    }
}
