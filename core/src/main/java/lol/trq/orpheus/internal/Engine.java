/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import lol.trq.orpheus.AppRules;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.DisplayRules;
import lol.trq.orpheus.MediaKind;
import lol.trq.orpheus.Playback;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.SessionInfo;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.Snapshot;
import lol.trq.orpheus.SourceInfo;
import lol.trq.orpheus.Timeline;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.cover.Cover;
import lol.trq.orpheus.cover.CoverCache;
import lol.trq.orpheus.event.CoverReady;
import lol.trq.orpheus.event.LyricsChanged;
import lol.trq.orpheus.event.OrpheusEvent;
import lol.trq.orpheus.event.PlaybackChanged;
import lol.trq.orpheus.event.Seeked;
import lol.trq.orpheus.event.SessionsChanged;
import lol.trq.orpheus.event.SourceStatusChanged;
import lol.trq.orpheus.event.StateChanged;
import lol.trq.orpheus.event.TrackChanged;
import lol.trq.orpheus.lyrics.Lyrics;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceStatus;

/**
 * The state machine behind {@code Orpheus}: selection, merging, position anchoring and change detection. It has no
 * threads and no clock of its own; one thread must drive it. Not API.
 */
public final class Engine {
    /** What the engine asks its owner to do. */
    public interface Host {
        void publish(Snapshot snapshot);

        void emit(OrpheusEvent event);

        /** Decode off-thread, then call {@link Engine#coverDecoded}. */
        void decodeCover(CoverData data);

        /** Look up off-thread, then call {@link Engine#lyricsFound}. */
        void findLyrics(long generation, Track track);

        /** Call {@link Engine#refresh} at or after this {@code nanoTime}. */
        void wakeAt(long nanos);
    }

    /** Where a command for a session has to go. */
    public record Target(Source source, String sessionId) {}

    static final long STICKY_NANOS = 3_000_000_000L;
    static final long REANCHOR_MILLIS = 100;
    static final long SEEK_MILLIS = 1000;
    private static final long DURATION_MILLIS = 2000;
    private static final int MAX_LYRIC_LOOKUPS = 4;
    private static final int MAX_OFFSETS = 256;

    private record Entry(int source, SessionUpdate update, boolean visible, String key) {}

    private final List<Source> sources;
    private final Host host;
    private final LongSupplier clock;
    private final CoverCache covers;
    private final List<List<SessionUpdate>> bySource = new ArrayList<>();
    private final Map<String, Long> changedAt = new HashMap<>();
    private final Map<String, SessionUpdate> last = new HashMap<>();
    private final Map<Control, Target> routes = new HashMap<>();
    private final Map<String, Long> offsets = new LinkedHashMap<>(16, 0.75f, true);
    private final List<Map<String, String>> keys = new ArrayList<>();
    private final List<SourceStatus> statuses = new ArrayList<>();
    private final Map<String, Boolean> visibility = new HashMap<>();
    private final Map<String, Boolean> videoVisibility = new HashMap<>();

    private AppRules rules;
    private Set<String> shareable;
    private String pinSource;
    private String pinApp;
    private String selected;
    private Snapshot snapshot = Snapshot.NONE;
    private Track rawTrack = Track.NONE;
    private long rawPosition;
    private long rawMeasured;
    private CoverData wantedCover;
    private String requestedCover;
    private String failedCover;
    private long lyricsDuration;
    private int lyricsLookups;
    private long lyricsGeneration;
    private List<SessionInfo> infos = List.of();

    public Engine(
            List<Source> sources,
            AppRules rules,
            Set<String> shareable,
            Host host,
            LongSupplier clock,
            CoverCache covers) {
        this.sources = List.copyOf(sources);
        this.rules = rules;
        this.shareable = Set.copyOf(shareable);
        this.host = host;
        this.clock = clock;
        this.covers = covers;
        for (int i = 0; i < sources.size(); i++) {
            bySource.add(List.of());
            keys.add(new HashMap<>());
            statuses.add(SourceStatus.STARTING);
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Every source in builder order with its latest status. */
    public List<SourceInfo> sourceInfos() {
        List<SourceInfo> out = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            out.add(new SourceInfo(
                    sources.get(i).id(), statuses.get(i), bySource.get(i).size()));
        }
        return out;
    }

    public List<SessionInfo> sessions() {
        return infos;
    }

    public void sessions(int source, List<SessionUpdate> sessions) {
        if (sessions.equals(bySource.get(source))) {
            return;
        }
        bySource.set(source, List.copyOf(sessions));
        refresh();
    }

    public void status(int source, SourceStatus status) {
        if (status.state() != SourceStatus.State.READY) {
            bySource.set(source, List.of());
            refresh();
        }
        statuses.set(source, status);
        host.emit(new SourceStatusChanged(snapshot, sources.get(source).id(), status));
    }

    public void rules(AppRules newRules) {
        rules = newRules;
        visibility.clear();
        videoVisibility.clear();
        refresh();
    }

    public void shareable(Set<String> sourceIds) {
        shareable = Set.copyOf(sourceIds);
        refresh();
    }

    public void pin(String sourceId, String appId) {
        pinSource = sourceId;
        pinApp = appId;
        refresh();
    }

    /** Sets the lyric offset of the playing song and remembers it for when the song returns. */
    public void lyricsOffset(long millis) {
        Playback playback = snapshot.playback();
        if (!playback.isPresent()) {
            return;
        }
        offsets.put(songKey(playback.track()), millis);
        if (offsets.size() > MAX_OFFSETS) {
            offsets.remove(offsets.keySet().iterator().next());
        }
        if (snapshot.lyrics() != Lyrics.NONE && snapshot.lyrics().offsetMillis() != millis) {
            publish(new Snapshot(playback, snapshot.lyrics().withOffset(millis), snapshot.cover()));
            host.emit(new LyricsChanged(snapshot));
        }
    }

    public void coverDecoded(String key, Cover cover) {
        if (cover != null) {
            covers.put(cover);
        }
        if (key.equals(requestedCover)) {
            requestedCover = null;
            failedCover = cover == null ? key : null;
        }
        if (cover != null && key.equals(snapshot.playback().coverKey()) && snapshot.cover() == null) {
            publish(new Snapshot(snapshot.playback(), snapshot.lyrics(), cover));
            host.emit(new CoverReady(snapshot));
        }
    }

    public void lyricsFound(long generation, Lyrics lyrics) {
        if (generation != lyricsGeneration || lyrics == null || lyrics == Lyrics.NONE) {
            return;
        }
        Long offset = offsets.get(songKey(snapshot.playback().track()));
        Lyrics shown = offset == null ? lyrics : lyrics.withOffset(offset);
        publish(new Snapshot(snapshot.playback(), shown, snapshot.cover()));
        host.emit(new LyricsChanged(snapshot));
    }

    /** Looks for lyrics again when the playing track has none, after a provider learned something new. */
    public void retryLyrics() {
        Playback playback = snapshot.playback();
        if (playback.isPresent()
                && snapshot.lyrics() == Lyrics.NONE
                && !playback.track().isEmpty()) {
            host.findLyrics(++lyricsGeneration, playback.track());
        }
    }

    /**
     * Finds where a command for the session of {@code playback} must go.
     *
     * @return the target, or null when the session is gone or does not accept the control
     */
    public Target route(Playback playback, Control control) {
        if (playback.sameSession(snapshot.playback())) {
            return routes.get(control);
        }
        for (int i = 0; i < sources.size(); i++) {
            if (!sources.get(i).id().equals(playback.sourceId())) {
                continue;
            }
            for (SessionUpdate update : bySource.get(i)) {
                if (update.sessionId().equals(playback.sessionId())
                        && update.controls().contains(control)) {
                    return new Target(sources.get(i), update.sessionId());
                }
            }
        }
        return null;
    }

    /** Whether the session of {@code playback} still exists. */
    public boolean alive(Playback playback) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id().equals(playback.sourceId())) {
                for (SessionUpdate update : bySource.get(i)) {
                    if (update.sessionId().equals(playback.sessionId())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The source of the selected session, or the first source when nothing is selected. */
    public Source selectedSource() {
        for (Source source : sources) {
            if (source.id().equals(snapshot.playback().sourceId())) {
                return source;
            }
        }
        return sources.isEmpty() ? null : sources.get(0);
    }

    /** Recomputes everything from the latest reports. Cheap when nothing changed. */
    public void refresh() {
        long now = clock.getAsLong();
        List<Entry> entries = new ArrayList<>();
        List<SessionInfo> newInfos = new ArrayList<>();
        Map<String, SessionUpdate> seen = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            for (SessionUpdate update : bySource.get(i)) {
                boolean visible = !source.foreign() || visible(update);
                Entry entry = new Entry(i, update, visible, key(i, update.sessionId()));
                entries.add(entry);
                newInfos.add(new SessionInfo(source.id(), update.sessionId(), update.app(), update.state(), visible));
                SessionUpdate before = last.get(entry.key());
                if (before == null || before.state() != update.state() || !sameTrack(before.track(), update.track())) {
                    changedAt.put(entry.key(), now);
                }
                seen.put(entry.key(), update);
            }
        }
        last.clear();
        last.putAll(seen);
        changedAt.keySet().retainAll(seen.keySet());

        Entry chosen = select(entries, now);
        selected = chosen == null ? null : chosen.key();
        if (chosen == null) {
            wantedCover = null;
        }
        Playback playback = chosen == null ? Playback.NONE : build(chosen, entries);
        apply(playback, chosen == null ? Track.NONE : chosen.update().track());

        if (!newInfos.equals(infos)) {
            infos = List.copyOf(newInfos);
            host.emit(new SessionsChanged(snapshot, infos));
        }
    }

    private boolean visible(SessionUpdate update) {
        boolean video = update.track().kind() == MediaKind.VIDEO;
        Map<String, Boolean> cache = video ? videoVisibility : visibility;
        Boolean known = cache.get(update.app().id());
        if (known == null) {
            known = rules.visible(update.app(), update.track().kind());
            if (cache.size() > 1024) {
                cache.clear();
            }
            cache.put(update.app().id(), known);
        }
        return known;
    }

    private String key(int source, String sessionId) {
        Map<String, String> known = keys.get(source);
        String key = known.get(sessionId);
        if (key == null) {
            key = source + "/" + sessionId;
            if (known.size() > 1024) {
                known.clear();
            }
            known.put(sessionId, key);
        }
        return key;
    }

    private Entry select(List<Entry> entries, long now) {
        Entry current = null;
        Entry best = null;
        for (Entry entry : entries) {
            if (!entry.visible() || !pinned(entry)) {
                continue;
            }
            if (entry.key().equals(selected)) {
                current = entry;
            }
            if (best == null || better(entry, best)) {
                best = entry;
            }
        }
        if (current == null || best == current) {
            return best;
        }
        if (rank(best) == 3 && best.source() < current.source()) {
            // a source earlier in the builder order started playing: it takes over at once
            return best;
        }
        if (rank(current) == 3 || rank(best) < 3) {
            return current;
        }
        long switchAt = changedAt.get(current.key()) + STICKY_NANOS;
        if (now - switchAt >= 0) {
            return best;
        }
        host.wakeAt(switchAt);
        return current;
    }

    private boolean pinned(Entry entry) {
        if (pinSource == null) {
            return true;
        }
        return sources.get(entry.source()).id().equals(pinSource)
                && (pinApp == null
                        || AppRules.matches(pinApp, entry.update().app().id()));
    }

    private boolean better(Entry a, Entry b) {
        if (rank(a) != rank(b)) {
            return rank(a) > rank(b);
        }
        if (a.source() != b.source()) {
            return a.source() < b.source();
        }
        return changedAt.get(a.key()) - changedAt.get(b.key()) > 0;
    }

    private static int rank(Entry entry) {
        return switch (entry.update().state()) {
            case PLAYING, BUFFERING -> 3;
            case PAUSED -> 2;
            case STOPPED -> 1;
            case NONE -> 0;
        };
    }

    private Playback build(Entry primary, List<Entry> entries) {
        SessionUpdate p = primary.update();
        Source source = sources.get(primary.source());
        Track track = p.track();
        Shuffle shuffle = p.shuffle();
        Repeat repeat = p.repeat();
        double volume = p.volume();
        CoverData cover = p.cover();
        String coverUri = p.coverUri();
        boolean share = shareable.contains(source.id());
        DisplayRules display = source.display();
        Set<Control> controls = new HashSet<>();
        controls.addAll(p.controls());
        routes.clear();
        for (Control control : controls) {
            routes.put(control, new Target(source, p.sessionId()));
        }
        for (Entry other : entries) {
            if (other.source() == primary.source()
                    || !other.visible()
                    || !track.sameSong(other.update().track())) {
                continue;
            }
            SessionUpdate s = other.update();
            Source otherSource = sources.get(other.source());
            track = fill(track, s.track());
            shuffle = shuffle == Shuffle.UNKNOWN ? s.shuffle() : shuffle;
            repeat = repeat == Repeat.UNKNOWN ? s.repeat() : repeat;
            volume = Double.isNaN(volume) ? s.volume() : volume;
            if (cover == null && coverUri == null) {
                cover = s.cover();
                coverUri = s.coverUri();
            }
            share &= shareable.contains(otherSource.id());
            display = strictest(display, otherSource.display());
            for (Control control : s.controls()) {
                if (controls.add(control)) {
                    routes.put(control, new Target(otherSource, s.sessionId()));
                }
            }
        }
        if (controls.contains(Control.PLAY) || controls.contains(Control.PAUSE)) {
            // toggling works whenever either half does; the command is routed to that half
            controls.add(Control.TOGGLE);
        }
        if (coverUri != null && coverUri.length() > 4 * Text.MAX_LENGTH) {
            coverUri = null;
        }
        wantedCover = cover;
        Playback before = snapshot.playback();
        boolean running = p.state() == PlaybackState.PLAYING;
        long position = Math.max(0, p.positionMillis());
        Timeline timeline = new Timeline(position, p.measuredNanos(), p.rate(), running, track.durationMillis());
        if (before.sourceId().equals(source.id())
                && before.sessionId().equals(p.sessionId())
                && sameTrack(rawTrack, p.track())) {
            Timeline old = before.timeline();
            boolean unchanged = old.running() == running
                    && old.rate() == timeline.rate()
                    && old.durationMillis() == timeline.durationMillis();
            // The same measurement as last time says nothing new about the position, whatever else changed.
            boolean stale = Math.abs(position - rawPosition) <= REANCHOR_MILLIS
                    && Math.abs(p.measuredNanos() - rawMeasured) <= REANCHOR_MILLIS * 1_000_000L;
            if (stale && !unchanged) {
                // A player paused, resumed or changed speed without measuring again: carry the position over.
                long now = clock.getAsLong();
                timeline = new Timeline(old.positionMillisAt(now), now, p.rate(), running, track.durationMillis());
            } else if (unchanged
                    && (stale || Math.abs(old.positionMillisAt(p.measuredNanos()) - position) <= REANCHOR_MILLIS)) {
                timeline = old;
            }
        }
        rawPosition = position;
        rawMeasured = p.measuredNanos();
        return new Playback(
                source.id(),
                p.sessionId(),
                p.app(),
                track,
                p.state() == PlaybackState.NONE ? PlaybackState.STOPPED : p.state(),
                timeline,
                shuffle,
                repeat,
                volume,
                Set.copyOf(controls),
                cover == null ? null : cover.key(),
                coverUri,
                share,
                display);
    }

    private void apply(Playback playback, Track raw) {
        Playback before = snapshot.playback();
        if (playback.equals(before)) {
            rawTrack = raw;
            return;
        }
        boolean trackChanged = !playback.sameSession(before) || !sameTrack(rawTrack, raw);
        boolean stateChanged = playback.state() != before.state();
        boolean seeked = !trackChanged
                && playback.timeline() != before.timeline()
                && Math.abs(before.timeline()
                                        .positionMillisAt(playback.timeline().anchorNanos())
                                - playback.timeline().positionMillis())
                        > SEEK_MILLIS;
        rawTrack = raw;

        // players report the title and the duration separately: lyrics matched with the wrong duration are dropped
        long duration = playback.track().durationMillis();
        boolean lookAgain = !trackChanged && duration > 0 && Math.abs(duration - lyricsDuration) > DURATION_MILLIS;
        Lyrics lyrics = trackChanged || lookAgain ? Lyrics.NONE : snapshot.lyrics();
        boolean lyricsDropped = lookAgain && snapshot.lyrics() != Lyrics.NONE;
        Cover cover = playback.coverKey() == null ? null : covers.get(playback.coverKey());
        if (cover == null && snapshot.cover() != null && snapshot.cover().key().equals(playback.coverKey())) {
            cover = snapshot.cover();
        }
        boolean coverArrived = cover != null && snapshot.cover() != cover;
        publish(new Snapshot(playback, lyrics, cover));

        if (cover == null
                && wantedCover != null
                && !wantedCover.key().equals(requestedCover)
                && !wantedCover.key().equals(failedCover)) {
            requestedCover = wantedCover.key();
            host.decodeCover(wantedCover);
        }
        if (trackChanged || lookAgain) {
            lyricsGeneration++;
            lyricsLookups = trackChanged ? 1 : lyricsLookups + 1;
            if (lyricsLookups <= MAX_LYRIC_LOOKUPS) {
                lyricsDuration = duration;
            }
            if (lyricsLookups <= MAX_LYRIC_LOOKUPS
                    && playback.isPresent()
                    && !playback.track().isEmpty()) {
                host.findLyrics(lyricsGeneration, playback.track());
            }
        }
        if (trackChanged) {
            host.emit(new TrackChanged(snapshot));
        }
        if (lyricsDropped) {
            host.emit(new LyricsChanged(snapshot));
        }
        if (stateChanged) {
            host.emit(new StateChanged(snapshot));
        }
        if (seeked) {
            host.emit(new Seeked(snapshot));
        }
        if (coverArrived) {
            host.emit(new CoverReady(snapshot));
        }
        host.emit(new PlaybackChanged(snapshot));
    }

    private void publish(Snapshot next) {
        snapshot = next;
        host.publish(next);
    }

    private static boolean sameTrack(Track a, Track b) {
        if (a.id() != null && b.id() != null && !a.id().equals(b.id())) {
            return false;
        }
        return a.title().equals(b.title()) && a.artists().equals(b.artists());
    }

    private static Track fill(Track a, Track b) {
        return new Track(
                a.id() != null ? a.id() : b.id(),
                a.title(),
                a.artists().isEmpty() ? b.artists() : a.artists(),
                a.album().isEmpty() ? b.album() : a.album(),
                a.albumArtist().isEmpty() ? b.albumArtist() : a.albumArtist(),
                a.durationMillis() > 0 ? a.durationMillis() : b.durationMillis(),
                a.trackNumber() > 0 ? a.trackNumber() : b.trackNumber(),
                a.uri() != null ? a.uri() : b.uri(),
                a.kind() != MediaKind.UNKNOWN ? a.kind() : b.kind());
    }

    private static DisplayRules strictest(DisplayRules a, DisplayRules b) {
        if (b == DisplayRules.NONE) {
            return a;
        }
        return new DisplayRules(
                a.artworkUnmodified() || b.artworkUnmodified(),
                a.attribution() != null ? a.attribution() : b.attribution(),
                a.linkBack() != null ? a.linkBack() : b.linkBack());
    }

    private static String songKey(Track track) {
        return Text.title(track.title()) + "|" + String.join(",", Text.artists(track.artists()));
    }
}
