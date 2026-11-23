/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import lol.trq.orpheus.AppRules;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.Orpheus;
import lol.trq.orpheus.Snapshot;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.event.SourceStatusChanged;
import lol.trq.orpheus.lyrics.Lrc;
import lol.trq.orpheus.lyrics.Lyrics;
import lol.trq.orpheus.lyrics.LyricsMatch;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.PlaybackUpdate;
import lol.trq.orpheus.source.PushSource;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.system.SystemSessions;

/**
 * A short end-to-end run used to prove that the library still works after its jars were repackaged, obfuscated and
 * downgraded to older bytecode. It prints one line and exits 0, or says what broke and exits 1.
 */
public final class Smoke {
    private Smoke() {}

    public static void main(String[] args) throws Exception {
        try {
            System.out.println(run());
            System.exit(0);
        } catch (Throwable e) {
            System.out.println("SMOKE FAILED on Java " + System.getProperty("java.version") + ": " + e);
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String run() throws Exception {
        PushSource push = PushSource.create("smoke-player");
        AtomicReference<SourceStatus> system = new AtomicReference<>(SourceStatus.STARTING);
        boolean[] paused = {false};
        push.handler(Control.PAUSE, request -> paused[0] = true);
        Orpheus orpheus = Orpheus.builder()
                .source(push)
                .source(SystemSessions.create())
                .apps(AppRules.none())
                .lyrics(track -> new LyricsMatch(
                        Lrc.parse("[00:01.00]first\n[00:05.00]second").lyrics(), 1.0))
                .build();
        orpheus.listen(event -> {
            if (event instanceof SourceStatusChanged changed
                    && changed.sourceId().equals("system")) {
                system.set(changed.status());
            }
        });
        orpheus.start();

        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        push.publish(PlaybackUpdate.playing(
                        new Track("id", "Smoke Song", List.of("Smoke Artist"), "", "", 60_000, 1, null, null))
                .position(Duration.ofSeconds(6))
                .cover(CoverData.of(png.toByteArray())));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Snapshot s = orpheus.snapshot();
            if (s.playback().isPresent()
                    && s.cover() != null
                    && s.lyrics().kind() == Lyrics.Kind.LINE
                    && system.get().state() != SourceStatus.State.STARTING) {
                break;
            }
            Thread.sleep(10);
        }
        Snapshot s = orpheus.snapshot();
        require(s.playback().track().title().equals("Smoke Song"), "the pushed track did not arrive");
        require(s.cover() != null && s.cover().width() == 8, "the cover was not decoded");
        require(
                s.lyrics().lineAt(s.playback().timeline().positionMillisAt(System.nanoTime())) == 1,
                "lyric lookup is off");
        ControlResult result = orpheus.controls().pause().get(10, TimeUnit.SECONDS);
        require(result.done() && paused[0], "the pause command did not reach the handler: " + result);
        require(
                orpheus.controls().next().get(10, TimeUnit.SECONDS).status()
                        == lol.trq.orpheus.ControlStatus.UNSUPPORTED,
                "next should be unsupported");

        SourceStatus status = system.get();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String reason = status.reason().toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            boolean loaderProblem =
                    reason.contains("native") || reason.contains("checksum") || reason.contains("bundled");
            require(!loaderProblem, "the Windows native library did not load: " + status.reason());
        }
        if ("1".equals(System.getenv("ORPHEUS_SMOKE_REQUIRE_READY"))) {
            require(
                    status.state() == SourceStatus.State.READY,
                    "the system source is " + status.state() + ": " + status.reason());
        }
        orpheus.stop();
        long threads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("orpheus-"))
                .count();
        require(threads == 0, threads + " library threads survived stop");
        return "SMOKE OK java=" + System.getProperty("java.version") + " system=" + status.state()
                + (status.reason().isEmpty() ? "" : " (" + status.reason() + ")");
    }
}
