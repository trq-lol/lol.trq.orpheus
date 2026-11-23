/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import lol.trq.orpheus.Orpheus;
import lol.trq.orpheus.Snapshot;
import lol.trq.orpheus.Subscription;
import lol.trq.orpheus.event.OrpheusEvent;
import lol.trq.orpheus.event.SourceStatusChanged;
import lol.trq.orpheus.source.SourceStatus;

/** Watches an {@link Orpheus} so a scenario can wait for a condition and learn how long it took. */
final class Probe implements AutoCloseable {
    final Orpheus orpheus;
    final List<OrpheusEvent> events = new CopyOnWriteArrayList<>();
    private final Subscription subscription;
    private final Object lock = new Object();
    private volatile SourceStatus status = SourceStatus.STARTING;

    Probe(Orpheus orpheus) {
        this.orpheus = orpheus;
        this.subscription = orpheus.listen(event -> {
            events.add(event);
            if (event instanceof SourceStatusChanged changed) {
                status = changed.status();
            }
            synchronized (lock) {
                lock.notifyAll();
            }
        });
    }

    SourceStatus status() {
        return status;
    }

    /**
     * Waits until the snapshot satisfies the condition.
     *
     * @return nanoseconds waited, or -1 on timeout
     */
    long await(Predicate<Snapshot> condition, long timeoutMillis) throws InterruptedException {
        return awaitThat(() -> condition.test(orpheus.snapshot()), timeoutMillis);
    }

    long awaitThat(java.util.function.BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long start = System.nanoTime();
        long deadline = start + timeoutMillis * 1_000_000L;
        synchronized (lock) {
            while (!condition.getAsBoolean()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return -1;
                }
                lock.wait(Math.max(1, Math.min(left / 1_000_000L, 20)));
            }
        }
        return System.nanoTime() - start;
    }

    @Override
    public void close() {
        subscription.close();
        orpheus.stop();
    }
}
