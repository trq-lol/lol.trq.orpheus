/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.ControlResult;
import lol.trq.orpheus.SourceApp;

/**
 * A source fed by the program itself: publish what your own player is doing and register handlers for the commands
 * it accepts. Thread-safe. Sessions of a push source are always visible.
 */
public final class PushSource implements Source, Transport {
    private static final String SESSION = "push";

    private final String id;
    private final SourceApp app;
    private final Map<Control, Consumer<ControlRequest>> handlers = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> capabilities = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private SourceSink sink;
    private PlaybackUpdate current;

    private PushSource(String id, String name) {
        this.id = id;
        this.app = new SourceApp(id, name);
    }

    public static PushSource create(String id) {
        return new PushSource(id, id);
    }

    /** Creates a source with a separate display name. */
    public static PushSource create(String id, String name) {
        return new PushSource(id, name);
    }

    @Override
    public String id() {
        return id;
    }

    /** Publishes the current state. Publish again whenever something changes, including after a seek. */
    public void publish(PlaybackUpdate update) {
        synchronized (lock) {
            current = update;
            report();
        }
    }

    /** Removes the session, as when the player closes. */
    public void clear() {
        publish(null);
    }

    /**
     * Registers what to do for a command. The handler runs on a library thread and must return quickly; throwing
     * reports the command as failed. Registered controls are advertised with the next {@link #publish}.
     */
    public PushSource handler(Control control, Consumer<ControlRequest> handler) {
        handlers.put(control, handler);
        synchronized (lock) {
            report();
        }
        return this;
    }

    /** Offers an extra ability such as {@link QueueAccess}, {@link Devices} or {@link LibraryAccess}. */
    public <T> PushSource provide(Class<T> type, T implementation) {
        capabilities.put(type, implementation);
        return this;
    }

    @Override
    public <T> T capability(Class<T> type) {
        Object provided = capabilities.get(type);
        if (provided != null) {
            return type.cast(provided);
        }
        return type.isInstance(this) ? type.cast(this) : null;
    }

    @Override
    public void start(SourceSink newSink) {
        synchronized (lock) {
            sink = newSink;
            sink.status(SourceStatus.READY);
            report();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            sink = null;
        }
    }

    @Override
    public CompletableFuture<ControlResult> send(ControlRequest request) {
        Consumer<ControlRequest> handler = handlers.get(request.control());
        if (handler == null) {
            return CompletableFuture.completedFuture(ControlResult.unsupported("no handler for " + request.control()));
        }
        try {
            handler.accept(request);
            return CompletableFuture.completedFuture(ControlResult.DONE);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(ControlResult.failed(String.valueOf(e.getMessage())));
        }
    }

    private void report() {
        if (sink == null) {
            return;
        }
        PlaybackUpdate u = current;
        if (u == null) {
            sink.sessions(List.of());
            return;
        }
        Set<Control> controls = Set.copyOf(handlers.keySet());
        sink.sessions(List.of(new SessionUpdate(
                SESSION,
                app,
                u.track,
                u.state,
                u.positionMillis,
                u.measuredNanos,
                u.rate,
                u.shuffle,
                u.repeat,
                u.volume,
                controls,
                u.cover,
                u.coverUri)));
    }
}
