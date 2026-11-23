/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import lol.trq.orpheus.Control;
import lol.trq.orpheus.Repeat;

/**
 * A command for one session. Only the field that belongs to the control is meaningful.
 *
 * @param sessionId the target session
 * @param control the command
 * @param positionMillis the target for {@link Control#SEEK}
 * @param value the target for {@link Control#RATE} and {@link Control#VOLUME}
 * @param on the target for {@link Control#SHUFFLE} and {@link Control#MUTE}
 * @param repeat the target for {@link Control#REPEAT}
 */
public record ControlRequest(
        String sessionId, Control control, long positionMillis, double value, boolean on, Repeat repeat) {
    public static ControlRequest of(String sessionId, Control control) {
        return new ControlRequest(sessionId, control, 0, 0, false, Repeat.OFF);
    }
}
