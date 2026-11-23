/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;

/** A sink that lets a test wait for a condition instead of sleeping. */
final class Recorder implements SourceSink {
    private final List<SourceStatus> statuses = new ArrayList<>();
    private List<SessionUpdate> sessions = List.of();

    @Override
    public synchronized void status(SourceStatus status) {
        statuses.add(status);
        notifyAll();
    }

    @Override
    public synchronized void sessions(List<SessionUpdate> list) {
        sessions = list;
        notifyAll();
    }

    synchronized List<SessionUpdate> awaitSessions(Predicate<List<SessionUpdate>> condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.test(sessions)) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                fail("timed out; last sessions: " + sessions);
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return sessions;
    }

    synchronized SourceStatus awaitStatus(Predicate<SourceStatus> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            for (SourceStatus status : statuses) {
                if (condition.test(status)) {
                    statuses.clear();
                    return status;
                }
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                fail("timed out; statuses: " + statuses);
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
    }
}
