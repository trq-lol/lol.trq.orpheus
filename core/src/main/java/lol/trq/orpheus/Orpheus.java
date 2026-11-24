/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lol.trq.orpheus.cover.Cover;
import lol.trq.orpheus.cover.CoverCache;
import lol.trq.orpheus.cover.CoverDecoder;
import lol.trq.orpheus.event.OrpheusEvent;
import lol.trq.orpheus.event.SessionsChanged;
import lol.trq.orpheus.event.SourceStatusChanged;
import lol.trq.orpheus.internal.Engine;
import lol.trq.orpheus.lyrics.Lyrics;
import lol.trq.orpheus.lyrics.LyricsMatch;
import lol.trq.orpheus.lyrics.LyricsMatcher;
import lol.trq.orpheus.lyrics.LyricsProvider;
import lol.trq.orpheus.source.ControlRequest;
import lol.trq.orpheus.source.CoverData;
import lol.trq.orpheus.source.Reply;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.source.Transport;

/**
 * The entry point: collects what the sources report, picks one session, and offers it as a snapshot, as events and
 * through commands.
 *
 * <p>Reading ({@link #snapshot()}, {@link #playback()}, {@link #lyrics()}, {@link #cover()}) is safe from any thread,
 * never blocks and allocates nothing. Listeners run on the executor given to the builder, or on a library thread, in
 * order. Command futures complete on a library thread. Nothing runs before {@link #start()} and no thread survives
 * {@link #stop()}, unless a source or a lyrics provider hangs for more than five seconds while it stops.
 */
public final class Orpheus implements AutoCloseable {
    private static final int MAX_WAITING_EVENTS = 256;
    private static final long STOP_MILLIS = 5000;

    private final List<Source> sources;
    private final List<LyricsProvider> providers;
    private final Executor userExecutor;
    private final Consumer<String> debug;
    private final int coverSize;
    private final long commandTimeoutMillis;
    private final CoverCache covers;
    private final List<Consumer<? super OrpheusEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Map<Object, CompletableFuture<Void>> stopping = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();

    private volatile Snapshot snapshot = Snapshot.NONE;
    private volatile List<SessionInfo> sessions = List.of();
    private volatile List<SourceInfo> sourceInfos = List.of();
    private volatile Run run;
    private Run last;
    private volatile AppRules rules;
    private volatile Set<String> shareable;
    private volatile String pinSource;
    private volatile String pinApp;

    private Orpheus(Builder builder) {
        this.sources = List.copyOf(builder.sources);
        this.providers = List.copyOf(builder.providers);
        this.userExecutor = builder.executor;
        this.debug = builder.debug;
        this.coverSize = builder.coverSize;
        this.commandTimeoutMillis = builder.commandTimeoutMillis;
        this.covers = new CoverCache(builder.coverCacheBytes);
        this.rules = builder.rules;
        this.shareable = Set.copyOf(builder.shareable);
        for (LyricsProvider provider : providers) {
            provider.onChange(() -> onHub(r -> r.engine.retryLyrics()));
        }
        Set<String> ids = new HashSet<>();
        for (Source source : sources) {
            if (!ids.add(source.id())) {
                throw new IllegalArgumentException("two sources share the id " + source.id());
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Starts the sources. Does nothing when already started. */
    public void start() {
        synchronized (lifecycle) {
            if (run == null) {
                Run previous = last;
                Run next = new Run(previous == null ? CompletableFuture.completedFuture(null) : previous.finished);
                run = next;
                if (previous != null && !previous.finished.isDone()) {
                    debug("start waits for the previous stop to finish");
                    // a stop asked for from a library thread is still winding down: start once it is done
                    previous.finished.whenComplete((done, error) -> next.begin());
                } else {
                    next.begin();
                }
            }
        }
    }

    /**
     * Stops the sources and every library thread, fails commands still in flight and clears the snapshot. Safe to
     * call from a listener and more than once. Can be started again afterwards.
     */
    public void stop() {
        Run ending;
        synchronized (lifecycle) {
            ending = run;
            run = null;
            if (ending != null) {
                last = ending;
            }
        }
        if (ending != null) {
            ending.end();
            synchronized (lifecycle) {
                if (run == null) {
                    clear();
                }
            }
        }
    }

    private void clear() {
        snapshot = Snapshot.NONE;
        sessions = List.of();
        sourceInfos = List.of();
    }

    @Override
    public void close() {
        stop();
    }

    /** Playback, lyrics and cover of this instant, guaranteed to belong together. */
    public Snapshot snapshot() {
        return snapshot;
    }

    public Playback playback() {
        return snapshot.playback();
    }

    public Lyrics lyrics() {
        return snapshot.lyrics();
    }

    /** The decoded cover of the playing track, or null. */
    public Cover cover() {
        return snapshot.cover();
    }

    /** Every session the sources report, including hidden ones (without track data). */
    public List<SessionInfo> sessions() {
        return sessions;
    }

    /**
     * The configured sources in priority order, each with its status. Before {@link #start()} every source is
     * listed as starting.
     */
    public List<SourceInfo> sources() {
        List<SourceInfo> current = sourceInfos;
        if (current.isEmpty()) {
            List<SourceInfo> idle = new ArrayList<>();
            for (Source source : sources) {
                idle.add(new SourceInfo(source.id(), SourceStatus.STARTING, 0));
            }
            return idle;
        }
        return current;
    }

    /**
     * Registers a listener for every event. If listeners fall more than 256 events behind, the oldest waiting events
     * are dropped; every event carries the state of its moment, so the newest one is always current.
     */
    public Subscription listen(Consumer<? super OrpheusEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Commands for whichever session is selected at the moment each command is sent. */
    public Controls controls() {
        return new Controls(this, null);
    }

    /** Commands for the session of {@code playback}, even if the selection moves on. */
    public Controls controls(Playback playback) {
        return new Controls(this, playback);
    }

    public PlayQueue queue() {
        return new PlayQueue(this);
    }

    public Outputs devices() {
        return new Outputs(this);
    }

    public Library library() {
        return new Library(this);
    }

    /**
     * Manual choice: shows only sessions of one source, for example only the operating system or only one
     * service. Among its sessions the usual automatic choice applies. See {@link #sources()} for the ids.
     */
    public void pin(String sourceId) {
        pin(sourceId, null);
    }

    /** Shows only sessions of one application of one source. {@code appId} is matched like an {@link AppRules} rule. */
    public void pin(String sourceId, String appId) {
        pinSource = sourceId;
        pinApp = appId;
        onHub(r -> r.engine.pin(sourceId, appId));
    }

    /**
     * Returns to the automatic choice: something playing beats something paused, and between equals the source
     * added to the builder first wins. So with the operating system first, a service is only shown while the
     * system has nothing playing.
     */
    public void unpin() {
        pin(null, null);
    }

    /** Replaces the rules deciding which applications are visible. */
    public void apps(AppRules newRules) {
        rules = newRules;
        onHub(r -> r.engine.rules(newRules));
    }

    /** Opts a source in or out of {@link Playback#shareable()}. Off for every source until set. */
    public void shareable(String sourceId, boolean share) {
        synchronized (lifecycle) {
            Set<String> next = new HashSet<>(shareable);
            if (share) {
                next.add(sourceId);
            } else {
                next.remove(sourceId);
            }
            shareable = Set.copyOf(next);
        }
        onHub(r -> r.engine.shareable(shareable));
    }

    /** Shifts the lyrics of the playing song; positive shows lines earlier. Remembered per song until {@link #stop()}; does nothing while nothing plays. */
    public void lyricsOffset(Duration offset) {
        onHub(r -> r.engine.lyricsOffset(offset.toMillis()));
    }

    CompletableFuture<ControlResult> send(Playback bound, ControlRequest request) {
        CompletableFuture<ControlResult> result = new CompletableFuture<>();
        if (!onHub(r -> r.dispatch(bound, request, result))) {
            result.complete(ControlResult.unsupported("not started"));
        }
        return result;
    }

    <C> CompletableFuture<ControlResult> act(
            Class<C> type, BiFunction<C, String, CompletableFuture<ControlResult>> call) {
        CompletableFuture<ControlResult> result = new CompletableFuture<>();
        if (!onHub(r -> r.call(type, call, result, ControlResult::failed, ControlResult::unsupported))) {
            result.complete(ControlResult.unsupported("not started"));
        }
        return result;
    }

    <C, T> CompletableFuture<Reply<T>> ask(
            Class<C> type, T empty, BiFunction<C, String, CompletableFuture<Reply<T>>> call) {
        CompletableFuture<Reply<T>> result = new CompletableFuture<>();
        if (!onHub(r -> r.call(
                type,
                call,
                result,
                why -> new Reply<>(ControlResult.failed(why), empty),
                why -> new Reply<>(ControlResult.unsupported(why), empty)))) {
            result.complete(new Reply<>(ControlResult.unsupported("not started"), empty));
        }
        return result;
    }

    private boolean onHub(Consumer<Run> task) {
        Run current = run;
        if (current == null) {
            return false;
        }
        return current.submit(current.hub, () -> task.accept(current));
    }

    private void debug(String message) {
        if (debug != null) {
            try {
                debug.accept(message);
            } catch (RuntimeException ignored) {
                // a broken log sink must not break playback tracking
            }
        }
    }

    /** One start-to-stop cycle: its threads, its engine and its commands in flight. */
    private final class Run implements Engine.Host {
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        final ScheduledThreadPoolExecutor hub = new ScheduledThreadPoolExecutor(1, factory("orpheus-hub"));
        final ExecutorService io = Executors.newSingleThreadExecutor(factory("orpheus-io"));
        final ExecutorService ownEvents =
                userExecutor == null ? Executors.newSingleThreadExecutor(factory("orpheus-events")) : null;
        final Executor events = userExecutor == null ? ownEvents : userExecutor;
        final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
        final List<Runnable> failPending = new CopyOnWriteArrayList<>();
        final Engine engine = new Engine(sources, rules, shareable, this, System::nanoTime, covers);
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        final CompletableFuture<Void> sourcesStopped = new CompletableFuture<>();
        final CompletableFuture<Void> previous;
        private final Map<Integer, Map<String, Brief>> briefs = new HashMap<>();
        private final ArrayDeque<OrpheusEvent> waiting = new ArrayDeque<>();
        private long dropped;
        private final AtomicReference<Runnable> nextCover = new AtomicReference<>();
        private final AtomicReference<Runnable> nextLyrics = new AtomicReference<>();
        volatile boolean ended;
        private boolean begun;
        private ScheduledFuture<?> wake;
        private long wakeNanos;

        Run(CompletableFuture<Void> previous) {
            this.previous = previous;
        }

        ThreadFactory factory(String name) {
            return task -> {
                Thread thread = new Thread(task, name);
                thread.setDaemon(true);
                threads.add(thread);
                return thread;
            };
        }

        boolean submit(Executor executor, Runnable task) {
            try {
                executor.execute(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        debug("task failed: " + e);
                    }
                });
                return true;
            } catch (RejectedExecutionException e) {
                return false;
            }
        }

        void begin() {
            hub.setRemoveOnCancelPolicy(true);
            submit(hub, () -> {
                if (ended) {
                    return;
                }
                begun = true;
                engine.rules(rules);
                engine.shareable(shareable);
                engine.pin(pinSource, pinApp);
                for (int i = 0; i < sources.size(); i++) {
                    int index = i;
                    CompletableFuture<Void> stop = stopping.get(sources.get(i));
                    if (stop == null) {
                        startSource(index);
                    } else {
                        engine.status(index, SourceStatus.unavailable("its previous stop has not finished"));
                        stop.whenComplete((done, error) -> submit(hub, () -> startSource(index)));
                    }
                }
            });
        }

        private void startSource(int index) {
            if (ended) {
                return;
            }
            SourceSink sink = new SourceSink() {
                @Override
                public void status(SourceStatus status) {
                    submit(hub, () -> engine.status(index, status));
                }

                @Override
                public void sessions(List<SessionUpdate> list) {
                    submit(hub, () -> {
                        engine.sessions(index, list);
                        tellOthers(index, list);
                    });
                }
            };
            try {
                sources.get(index).start(sink);
            } catch (RuntimeException | LinkageError e) {
                engine.status(index, SourceStatus.unavailable(e.toString()));
            }
        }

        void end() {
            ended = true;
            Runnable shutdown = () -> {
                try {
                    if (wake != null) {
                        wake.cancel(false);
                    }
                    List<Thread> stops = new ArrayList<>();
                    for (Source source : begun ? sources : List.<Source>of()) {
                        stops.add(stopAside(source, "source " + source.id(), source::stop));
                    }
                    joinStops(stops);
                    for (Runnable fail : failPending) {
                        fail.run();
                    }
                } finally {
                    sourcesStopped.complete(null);
                }
            };
            boolean inside = threads.contains(Thread.currentThread());
            if (Thread.currentThread().getName().equals("orpheus-hub") && inside) {
                shutdown.run();
            } else {
                try {
                    Future<?> done = hub.submit(shutdown);
                    if (!inside) {
                        done.get(10, TimeUnit.SECONDS);
                    }
                } catch (RejectedExecutionException e) {
                    sourcesStopped.complete(null);
                } catch (Exception e) {
                    debug("stop did not finish cleanly: " + e);
                }
            }
            io.shutdownNow();
            if (!inside) {
                try {
                    io.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            List<Thread> closes = new ArrayList<>();
            for (LyricsProvider provider : providers) {
                if (provider instanceof AutoCloseable closeable) {
                    closes.add(stopAside(provider, "a lyrics provider", closeable));
                }
            }
            joinStops(closes);
            hub.shutdown();
            if (ownEvents != null) {
                ownEvents.shutdown();
            }
            if (!inside) {
                try {
                    hub.awaitTermination(5, TimeUnit.SECONDS);
                    if (ownEvents != null) {
                        ownEvents.awaitTermination(5, TimeUnit.SECONDS);
                    }
                    // an executor reports termination a moment before its thread has really ended
                    for (Thread thread : threads) {
                        thread.join(2000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // a later run may begin only now, so nothing of this one can overwrite or close what it uses
            CompletableFuture.allOf(sourcesStopped, previous).whenComplete((done, error) -> {
                clear();
                finished.complete(null);
            });
        }

        private void tellOthers(int index, List<SessionUpdate> list) {
            if (sources.size() == 1) {
                return;
            }
            Map<String, Brief> now = new HashMap<>();
            for (SessionUpdate update : list) {
                now.put(
                        update.sessionId(),
                        new Brief(update.app(), update.track().title(), update.state()));
            }
            Map<String, Brief> before = briefs.put(index, now);
            Set<SourceApp> changed = new HashSet<>();
            now.forEach((id, brief) -> {
                if (before == null || !brief.equals(before.get(id))) {
                    changed.add(brief.app());
                }
            });
            if (before != null) {
                before.forEach((id, brief) -> {
                    if (!now.containsKey(id)) {
                        changed.add(brief.app());
                    }
                });
            }
            for (int i = 0; i < sources.size(); i++) {
                for (SourceApp app : changed) {
                    if (i != index) {
                        try {
                            sources.get(i).changedElsewhere(app);
                        } catch (RuntimeException | LinkageError e) {
                            debug("source " + sources.get(i).id() + " failed: " + e);
                        }
                    }
                }
            }
        }

        /** Runs a stop on its own thread, so one that hangs cannot block the others or a later start. */
        private Thread stopAside(Object owner, String what, AutoCloseable stop) {
            CompletableFuture<Void> stopped = new CompletableFuture<>();
            if (stopping.putIfAbsent(owner, stopped) != null) {
                return null;
            }
            Thread thread = factory("orpheus-stop").newThread(() -> {
                try {
                    stop.close();
                } catch (Exception | LinkageError e) {
                    debug(what + " failed to stop: " + e);
                } finally {
                    stopping.remove(owner);
                    stopped.complete(null);
                }
            });
            thread.start();
            return thread;
        }

        private void joinStops(List<Thread> stops) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_MILLIS);
            for (Thread thread : stops) {
                if (thread == null) {
                    continue;
                }
                try {
                    thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (thread.isAlive()) {
                    debug("a source or lyrics provider is still stopping");
                }
            }
        }

        void dispatch(Playback bound, ControlRequest request, CompletableFuture<ControlResult> result) {
            Playback playback = bound != null ? bound : engine.snapshot().playback();
            if (!playback.isPresent()) {
                result.complete(ControlResult.unsupported("nothing is playing"));
                return;
            }
            Control control = request.control();
            Engine.Target target = engine.route(playback, control);
            if (target == null && control == Control.TOGGLE) {
                control = playback.playing() ? Control.PAUSE : Control.PLAY;
                target = engine.route(playback, control);
            }
            if (target == null) {
                result.complete(
                        engine.alive(playback)
                                ? ControlResult.unsupported("the session does not accept " + request.control())
                                : ControlResult.refused("the session is gone"));
                return;
            }
            Transport transport = target.source().capability(Transport.class);
            if (transport == null) {
                result.complete(ControlResult.unsupported("the source has no transport"));
                return;
            }
            ControlRequest routed = new ControlRequest(
                    target.sessionId(),
                    control,
                    request.positionMillis(),
                    request.value(),
                    request.on(),
                    request.repeat());
            guard(() -> transport.send(routed), result, ControlResult::failed);
        }

        <C, R> void call(
                Class<C> type,
                BiFunction<C, String, CompletableFuture<R>> call,
                CompletableFuture<R> result,
                Function<String, R> failed,
                Function<String, R> unsupported) {
            C capability = engine.capability(type);
            if (capability == null) {
                result.complete(unsupported.apply("not supported by the selected source"));
                return;
            }
            String session = engine.snapshot().playback().sessionId();
            guard(() -> call.apply(capability, session), result, failed);
        }

        <R> void guard(Supplier<CompletableFuture<R>> call, CompletableFuture<R> result, Function<String, R> failed) {
            if (ended) {
                result.complete(failed.apply("stopped"));
                return;
            }
            Runnable fail = () -> result.complete(failed.apply("stopped"));
            ScheduledFuture<?> timeout;
            try {
                timeout = hub.schedule(
                        () -> result.complete(failed.apply("timed out")), commandTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                result.complete(failed.apply("stopped"));
                return;
            }
            pending.add(result);
            failPending.add(fail);
            result.whenComplete((value, error) -> {
                timeout.cancel(false);
                pending.remove(result);
                failPending.remove(fail);
            });
            try {
                CompletableFuture<R> inner = call.get();
                if (inner == null) {
                    result.complete(failed.apply("the source returned nothing"));
                    return;
                }
                inner.whenComplete((value, error) ->
                        result.complete(error != null || value == null ? failed.apply(String.valueOf(error)) : value));
            } catch (RuntimeException | LinkageError e) {
                result.complete(failed.apply(e.toString()));
            }
        }

        @Override
        public void publish(Snapshot next) {
            if (!ended) {
                snapshot = next;
            }
        }

        @Override
        public void emit(OrpheusEvent event) {
            if (ended) {
                return;
            }
            if (event instanceof SessionsChanged changed) {
                sessions = changed.sessions();
            }
            if (event instanceof SessionsChanged || event instanceof SourceStatusChanged) {
                sourceInfos = List.copyOf(engine.sourceInfos());
            }
            if (listeners.isEmpty()) {
                return;
            }
            // a task delivers everything that waits; a full queue still gets one at times, as executors may drop tasks
            synchronized (waiting) {
                boolean full = waiting.size() >= MAX_WAITING_EVENTS;
                if (full) {
                    waiting.poll();
                }
                waiting.add(event);
                if (full && ++dropped % MAX_WAITING_EVENTS != 0) {
                    return;
                }
            }
            try {
                events.execute(this::deliver);
            } catch (RuntimeException e) {
                synchronized (waiting) {
                    waiting.pollLast();
                }
                debug("event dropped: executor rejected it");
            }
        }

        private void deliver() {
            while (!ended) {
                OrpheusEvent event;
                synchronized (waiting) {
                    event = waiting.poll();
                }
                if (event == null) {
                    return;
                }
                for (Consumer<? super OrpheusEvent> listener : listeners) {
                    try {
                        listener.accept(event);
                    } catch (RuntimeException e) {
                        debug("listener failed: " + e);
                    }
                }
            }
        }

        /** Runs the newest task of a slot on the worker; an older one that has not begun is dropped. */
        private void submitLatest(AtomicReference<Runnable> slot, Runnable task) {
            if (slot.getAndSet(task) == null) {
                submit(io, () -> {
                    Runnable latest = slot.getAndSet(null);
                    if (latest != null) {
                        latest.run();
                    }
                });
            }
        }

        @Override
        public void decodeCover(CoverData data) {
            submitLatest(nextCover, () -> {
                Cover cover = CoverDecoder.decode(data.key(), data.bytes(), coverSize);
                submit(hub, () -> engine.coverDecoded(data.key(), cover));
            });
        }

        @Override
        public void findLyrics(long generation, Track track) {
            if (providers.isEmpty()) {
                return;
            }
            submitLatest(nextLyrics, () -> {
                List<Lyrics> found = new ArrayList<>(1);
                for (LyricsProvider provider : providers) {
                    try {
                        LyricsMatch match = provider.find(track);
                        if (match != null
                                && match.lyrics() != null
                                && match.lyrics().kind() != Lyrics.Kind.NONE
                                && match.confidence() >= LyricsMatcher.ACCEPT) {
                            found.add(match.lyrics().withMatch(match.lyrics().source(), match.confidence()));
                            break;
                        }
                    } catch (Exception e) {
                        debug("lyrics provider failed: " + e);
                    }
                }
                if (!found.isEmpty()) {
                    submit(hub, () -> engine.lyricsFound(generation, found.get(0)));
                }
            });
        }

        @Override
        public void wakeAt(long nanos) {
            if (ended) {
                return;
            }
            if (wake != null) {
                if (wakeNanos - nanos <= 0) {
                    return;
                }
                wake.cancel(false);
            }
            wakeNanos = nanos;
            try {
                wake = hub.schedule(
                        () -> {
                            wake = null;
                            try {
                                engine.refresh();
                            } catch (RuntimeException e) {
                                debug("refresh failed: " + e);
                            }
                        },
                        Math.max(0, nanos - System.nanoTime()) + 1_000_000,
                        TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException ignored) {
                // stopping
            }
        }
    }

    /** The part of a session whose change other sources are told about. */
    private record Brief(SourceApp app, String title, PlaybackState state) {}

    /** Configures an {@link Orpheus}. */
    public static final class Builder {
        private final List<Source> sources = new ArrayList<>();
        private final List<LyricsProvider> providers = new ArrayList<>();
        private final Set<String> shareable = new HashSet<>();
        private AppRules rules = AppRules.defaults();
        private Executor executor;
        private Consumer<String> debug;
        private int coverSize = 512;
        private long coverCacheBytes = 32L << 20;
        private long commandTimeoutMillis = 5000;

        private Builder() {}

        /**
         * Adds a source. Order is priority: an earlier source is chosen over a later one in the same play state,
         * and wins when two describe the same song.
         */
        public Builder source(Source source) {
            sources.add(source);
            return this;
        }

        /** Adds a lyrics provider. Providers are asked in order until one gives a confident match. */
        public Builder lyrics(LyricsProvider provider) {
            providers.add(provider);
            return this;
        }

        /** Sets which applications of operating system sources are visible. Default: {@link AppRules#defaults()}. */
        public Builder apps(AppRules appRules) {
            rules = appRules;
            return this;
        }

        /** Runs listeners on this executor instead of a library thread. */
        public Builder executor(Executor eventExecutor) {
            executor = eventExecutor;
            return this;
        }

        /** Marks the data of a source as shareable with other people. Off by default. */
        public Builder shareable(String sourceId) {
            shareable.add(sourceId);
            return this;
        }

        /** Receives diagnostic lines, which may contain track titles. None are produced without this. */
        public Builder debug(Consumer<String> sink) {
            debug = sink;
            return this;
        }

        /** The longest side of decoded covers in pixels. Default 512. */
        public Builder coverSize(int pixels) {
            coverSize = Math.max(1, pixels);
            return this;
        }

        /** The memory limit of the decoded cover cache. Default 32 MiB. */
        public Builder coverCacheBytes(long bytes) {
            coverCacheBytes = Math.max(0, bytes);
            return this;
        }

        /** How long a command may stay unanswered before it fails. Default 5 seconds. */
        public Builder commandTimeout(Duration timeout) {
            commandTimeoutMillis = Math.max(1, timeout.toMillis());
            return this;
        }

        public Orpheus build() {
            return new Orpheus(this);
        }
    }
}
