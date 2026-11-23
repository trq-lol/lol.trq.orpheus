/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.testkit;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.DisplayRules;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.SourceApp;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.source.Transport;

public final class FakeSource implements Source, Transport {
    public final List<ControlRequest> requests = new CopyOnWriteArrayList<>();
    public volatile CompletableFuture<ControlResult> answer = CompletableFuture.completedFuture(ControlResult.DONE);
    public volatile boolean failStart;
    public volatile boolean stopped;
    public DisplayRules display = DisplayRules.NONE;
    private final String id;
    private final boolean foreign;
    private volatile SourceSink sink;

    public FakeSource(String id, boolean foreign) {
        this.id = id;
        this.foreign = foreign;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean foreign() {
        return foreign;
    }

    @Override
    public DisplayRules display() {
        return display;
    }

    @Override
    public void start(SourceSink newSink) {
        if (failStart) {
            throw new IllegalStateException("cannot start");
        }
        sink = newSink;
        newSink.status(SourceStatus.READY);
    }

    @Override
    public void stop() {
        stopped = true;
        sink = null;
    }

    public void report(SessionUpdate... sessions) {
        sink.sessions(List.of(sessions));
    }

    @Override
    public CompletableFuture<ControlResult> send(ControlRequest request) {
        requests.add(request);
        return answer;
    }

    public static Session session(String id) {
        return new Session(id);
    }

    /** A small builder so a test states only what matters to it. */
    public static final class Session {
        private final String id;
        private String app = "player";
        private Track track = Track.of("Song", "Artist");
        private PlaybackState state = PlaybackState.PLAYING;
        private long position;
        private long measured;
        private double rate = 1;
        private Shuffle shuffle = Shuffle.UNKNOWN;
        private double volume = Double.NaN;
        private final Set<Control> controls = EnumSet.noneOf(Control.class);
        private CoverData cover;

        private Session(String id) {
            this.id = id;
        }

        public Session app(String value) {
            app = value;
            return this;
        }

        public Session track(Track value) {
            track = value;
            return this;
        }

        public Session track(String title, String artist) {
            track = Track.of(title, artist);
            return this;
        }

        public Session state(PlaybackState value) {
            state = value;
            return this;
        }

        public Session at(long positionMillis, long measuredNanos) {
            position = positionMillis;
            measured = measuredNanos;
            return this;
        }

        public Session rate(double value) {
            rate = value;
            return this;
        }

        public Session shuffle(Shuffle value) {
            shuffle = value;
            return this;
        }

        public Session volume(double value) {
            volume = value;
            return this;
        }

        public Session can(Control... values) {
            controls.addAll(List.of(values));
            return this;
        }

        public Session cover(CoverData value) {
            cover = value;
            return this;
        }

        public SessionUpdate build() {
            return new SessionUpdate(
                    id,
                    new SourceApp(app, app),
                    track,
                    state,
                    position,
                    measured,
                    rate,
                    shuffle,
                    Repeat.UNKNOWN,
                    volume,
                    Set.copyOf(new ArrayList<>(controls)),
                    cover,
                    null);
        }
    }
}
