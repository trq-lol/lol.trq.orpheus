/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.dbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BusAddressTest {
    private static String socket(String address) throws WireException {
        return BusAddress.sessionSocket(Map.of("DBUS_SESSION_BUS_ADDRESS", address));
    }

    @Test
    void findsThePathInEveryAddressShape() throws Exception {
        assertEquals("/run/user/1000/bus", socket("unix:path=/run/user/1000/bus"));
        assertEquals("/tmp/dbus-x", socket("unix:path=/tmp/dbus-x,guid=0123abcd"));
        assertEquals("/tmp/second", socket("tcp:host=localhost,port=1;unix:guid=1,path=/tmp/second"));
        assertEquals("/tmp/with space", socket("unix:path=/tmp/with%20space"));
        assertEquals("/run/user/7/bus", BusAddress.sessionSocket(Map.of("XDG_RUNTIME_DIR", "/run/user/7")));
    }

    @Test
    void explainsWhyThereIsNoUsableBus() {
        assertTrue(assertThrows(WireException.class, () -> socket("unix:abstract=/tmp/dbus-abc,guid=1"))
                .getMessage()
                .contains("abstract"));
        assertThrows(WireException.class, () -> socket("tcp:host=localhost,port=1"));
        assertThrows(WireException.class, () -> BusAddress.sessionSocket(Map.of()));
    }
}
