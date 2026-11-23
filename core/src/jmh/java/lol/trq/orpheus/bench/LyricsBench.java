/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.bench;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.cover.Cover;
import lol.trq.orpheus.cover.CoverDecoder;
import lol.trq.orpheus.lyrics.Lrc;
import lol.trq.orpheus.lyrics.LyricsMatcher;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Work done once per track, off the reading threads: lyric parsing and matching, cover decoding. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class LyricsBench {
    private String lrc;
    private Track track;
    private byte[] png;

    @Setup
    public void setup() throws IOException {
        StringBuilder text = new StringBuilder("[ti:A Song]\n[ar:An Artist]\n[length:04:00]\n");
        for (int i = 0; i < 80; i++) {
            text.append(String.format(
                    "[%02d:%02d.%02d]a line of generated lyrics number %d%n", i * 3 / 60, i * 3 % 60, i % 100, i));
        }
        lrc = text.toString();
        track = new Track(
                null,
                "A Song (feat. Someone) - Remastered 2011",
                List.of("An Artist, Someone"),
                "",
                "",
                240_000,
                0,
                null,
                null);
        BufferedImage image = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 640; y++) {
            for (int x = 0; x < 640; x++) {
                image.setRGB(x, y, (x * 255 / 640) << 16 | (y * 255 / 640) << 8 | (x ^ y) & 0xFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        png = out.toByteArray();
    }

    @Benchmark
    public Lrc parseLrc() {
        return Lrc.parse(lrc);
    }

    @Benchmark
    public double matchScore() {
        return LyricsMatcher.score(track, "A Song", List.of("An Artist"), 239_000);
    }

    @Benchmark
    public Cover decodeCover() {
        return CoverDecoder.decode("k", png, 512);
    }
}
