/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.spotify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UrlConnectionCallTest {
    private static final Map<String, String> TOKEN = Map.of("Authorization", "Bearer t");

    private final UrlConnectionCall call = new UrlConnectionCall();
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private HttpServer server;
    private String base;

    @BeforeEach
    void open() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(threads);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void close() {
        server.stop(0);
        threads.shutdownNow();
    }

    @Test
    void aRedirectIsNotFollowedWithTheToken() throws IOException {
        AtomicReference<String> leaked = new AtomicReference<>();
        server.createContext("/a", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/b");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/b", exchange -> {
            leaked.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        assertEquals(302, call.send("GET", base + "/a", TOKEN, null, 100).status());
        assertNull(leaked.get());
    }

    @Test
    void sendsBodiesAndEmptyBodiesAndReadsErrors() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestMethod()
                    + " " + exchange.getRequestHeaders().getFirst("Content-Length")
                    + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "7");
            exchange.sendResponseHeaders(429, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        HttpCall.Answer answer = call.send("PUT", base + "/", TOKEN, null, 100);
        assertEquals("PUT 0 ", seen.get());
        assertEquals(429, answer.status());
        assertEquals(7, answer.retryAfterSeconds());
        assertEquals("{}", new String(answer.body(), StandardCharsets.UTF_8));
        call.send("PUT", base + "/", TOKEN, "abc".getBytes(StandardCharsets.UTF_8), 100);
        assertEquals("PUT 3 abc", seen.get());
    }

    @Test
    void aPutThatIsNotAuthorizedStillGivesItsStatus() throws IOException {
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        assertEquals(
                401, call.send("PUT", base + "/", TOKEN, new byte[] {1}, 100).status());
    }

    @Test
    void aLongAnswerIsRefused() {
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[1000]);
            exchange.close();
        });
        assertThrows(IOException.class, () -> call.send("GET", base + "/", TOKEN, null, 999));
    }

    @Test
    void abortEndsARequestThatHangs() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            arrived.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        Future<?> request = threads.submit(() -> call.send("GET", base + "/", TOKEN, null, 100));
        arrived.await(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        call.abort();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(Exception.class, request::get));
        release.countDown();
    }
}
