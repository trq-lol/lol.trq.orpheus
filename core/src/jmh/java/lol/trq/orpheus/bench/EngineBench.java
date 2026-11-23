/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.bench;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lol.trq.orpheus.AppRules;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.Snapshot;
import lol.trq.orpheus.SourceApp;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.cover.CoverCache;
import lol.trq.orpheus.event.OrpheusEvent;
import lol.trq.orpheus.internal.Engine;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.PushSource;
import lol.trq.orpheus.source.SessionUpdate;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** The cost of one report from a source: selection, merging, change detection, snapshot and events. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EngineBench {
    private Engine engine;
    private List<SessionUpdate> playing;
    private List<SessionUpdate> paused;
    private List<SessionUpdate> unchanged;
    private boolean flip;
    private Blackhole sink;

    private static SessionUpdate session(String id, String title, PlaybackState state, long position) {
        return new SessionUpdate(
                id,
                new SourceApp("spotify", "Player"),
                new Track(null, title, List.of("An Artist"), "An Album", "", 200_000, 1, null, null),
                state,
                position,
                1_000_000_000L,
                1,
                Shuffle.OFF,
                Repeat.OFF,
                Double.NaN,
                Set.of(Control.PLAY, Control.PAUSE, Control.NEXT, Control.SEEK),
                null,
                null);
    }

    @Setup
    public void setup(Blackhole blackhole) {
        sink = blackhole;
        Engine.Host host = new Engine.Host() {
            @Override
            public void publish(Snapshot snapshot) {
                sink.consume(snapshot);
            }

            @Override
            public void emit(OrpheusEvent event) {
                sink.consume(event);
            }

            @Override
            public void decodeCover(CoverData data) {}

            @Override
            public void findLyrics(long generation, Track track) {}

            @Override
            public void wakeAt(long nanos) {}
        };
        engine = new Engine(
                List.of(PushSource.create("a"), PushSource.create("b")),
                AppRules.defaults(),
                Set.of(),
                host,
                () -> 2_000_000_000L,
                new CoverCache(1 << 20));
        SessionUpdate other = session("other", "Another Song", PlaybackState.PAUSED, 5_000);
        playing = List.of(session("main", "A Song", PlaybackState.PLAYING, 60_000), other);
        paused = List.of(session("main", "A Song", PlaybackState.PAUSED, 60_000), other);
        unchanged = playing;
        engine.sessions(1, List.of(session("svc", "A Song", PlaybackState.PLAYING, 60_000)));
        engine.sessions(0, playing);
    }

    /** A report that changes the state: a new snapshot and two events. */
    @Benchmark
    public Snapshot changedReport() {
        flip = !flip;
        engine.sessions(0, flip ? paused : playing);
        return engine.snapshot();
    }

    /** A report that changes nothing, as most periodic ones do. */
    @Benchmark
    public Snapshot unchangedReport() {
        engine.sessions(0, unchanged);
        return engine.snapshot();
    }
}
