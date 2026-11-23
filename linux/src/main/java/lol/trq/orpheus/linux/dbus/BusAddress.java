/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.dbus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Finds the socket path of the session bus from the environment. */
public final class BusAddress {
    private BusAddress() {}

    /**
     * @return the socket path
     * @throws WireException with a reason a user can read when there is no usable bus
     */
    public static String sessionSocket(Map<String, String> environment) throws WireException {
        String address = environment.get("DBUS_SESSION_BUS_ADDRESS");
        if (address == null || address.isBlank()) {
            String runtime = environment.get("XDG_RUNTIME_DIR");
            if (runtime == null || runtime.isBlank()) {
                throw new WireException("no session bus: DBUS_SESSION_BUS_ADDRESS and XDG_RUNTIME_DIR are not set");
            }
            return runtime + "/bus";
        }
        boolean abstractOnly = false;
        for (String entry : address.split(";")) {
            if (!entry.startsWith("unix:")) {
                continue;
            }
            for (String pair : entry.substring(5).split(",")) {
                if (pair.startsWith("path=")) {
                    return unescape(pair.substring(5));
                }
                abstractOnly |= pair.startsWith("abstract=");
            }
        }
        throw new WireException(
                abstractOnly
                        ? "the session bus uses an abstract socket, which Java cannot open"
                        : "the session bus address has no unix socket path");
    }

    private static String unescape(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() + 0 && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }
}
