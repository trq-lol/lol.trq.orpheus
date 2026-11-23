/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.dbus;

import java.io.IOException;

/** A D-Bus message that breaks the wire format or one of this client's limits. */
public final class WireException extends IOException {
    private static final long serialVersionUID = 1L;

    public WireException(String message) {
        super(message);
    }
}
