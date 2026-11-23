/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import lol.trq.orpheus.source.SessionUpdate;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.source.Transport;
import org.junit.jupiter.api.Test;

class SystemSessionsTest {
    @Test
    void everySupportedSystemGetsASourceThatCanSendCommands() {
        for (String os : List.of("Windows 11", "Linux", "Mac OS X")) {
            SystemSessions source = SystemSessions.forSystem(os);
            assertEquals("system", source.id());
            assertNotNull(source.capability(Transport.class), os);
        }
    }

    @Test
    void anUnknownSystemIsUnavailableWithItsName() {
        SystemSessions source = SystemSessions.forSystem("Plan 9");
        List<SourceStatus> statuses = new ArrayList<>();
        source.start(new SourceSink() {
            @Override
            public void status(SourceStatus status) {
                statuses.add(status);
            }

            @Override
            public void sessions(List<SessionUpdate> sessions) {}
        });
        assertEquals(SourceStatus.State.UNAVAILABLE, statuses.get(0).state());
        assertEquals("no media session support for Plan 9", statuses.get(0).reason());
        assertNull(source.capability(Transport.class));
        source.stop();
    }
}
