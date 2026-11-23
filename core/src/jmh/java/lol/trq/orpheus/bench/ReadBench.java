/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.DisplayRules;
import lol.trq.orpheus.Playback;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.Snapshot;
import lol.trq.orpheus.SourceApp;
import lol.trq.orpheus.Timeline;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.lyrics.LyricLine;
import lol.trq.orpheus.lyrics.LyricWord;
import lol.trq.orpheus.lyrics.Lyrics;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** What a render loop does every frame. All of these must allocate nothing. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ReadBench {
    private volatile Snapshot snapshot;
    private long now;

    @Setup
    public void setup() {
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            long start = i * 3000L;
            lines.add(new LyricLine(
                    start,
                    start + 3000,
                    "line " + i,
                    List.of(
                            new LyricWord(start, "line "),
                            new LyricWord(start + 1000, "number "),
                            new LyricWord(start + 2000, "" + i)),
                    "",
                    ""));
        }
        Playback playback = new Playback(
                "source",
                "session",
                new SourceApp("player", "Player"),
                new Track(
                        "id", "A Song", List.of("First Artist", "Second Artist"), "Album", "", 240_000, 3, null, null),
                PlaybackState.PLAYING,
                new Timeline(60_000, System.nanoTime(), 1, true, 240_000),
                Shuffle.OFF,
                Repeat.OFF,
                Double.NaN,
                Set.of(Control.PLAY, Control.PAUSE, Control.SEEK, Control.NEXT),
                null,
                null,
                false,
                DisplayRules.NONE);
        snapshot = new Snapshot(playback, new Lyrics(Lyrics.Kind.WORD, lines, 0, "", 1), null);
        now = System.nanoTime() + 5_000_000_000L;
    }

    @Benchmark
    public float progress() {
        return snapshot.playback().timeline().progressAt(now);
    }

    @Benchmark
    public int currentLine() {
        Snapshot s = snapshot;
        return s.lyrics().lineAt(s.playback().timeline().positionMillisAt(now));
    }

    @Benchmark
    public int currentWord() {
        Snapshot s = snapshot;
        long position = s.playback().timeline().positionMillisAt(now);
        return s.lyrics().wordAt(s.lyrics().lineAt(position), position);
    }

    @Benchmark
    public int frame() {
        Snapshot s = snapshot;
        Playback p = s.playback();
        long position = p.timeline().positionMillisAt(now);
        int line = s.lyrics().lineAt(position);
        return p.track().title().length()
                + p.track().artistLine().length()
                + (p.can(Control.SEEK) ? 1 : 0)
                + line
                + (int) (p.timeline().progressAt(now) * 100);
    }
}
