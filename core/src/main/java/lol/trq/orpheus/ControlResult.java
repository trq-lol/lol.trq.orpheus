/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/**
 * The outcome of a command.
 *
 * @param status how it ended
 * @param reason a short explanation for anything but {@link ControlStatus#DONE}; may be empty
 */
public record ControlResult(ControlStatus status, String reason) {
    public static final ControlResult DONE = new ControlResult(ControlStatus.DONE, "");

    public static ControlResult unsupported(String reason) {
        return new ControlResult(ControlStatus.UNSUPPORTED, reason);
    }

    public static ControlResult refused(String reason) {
        return new ControlResult(ControlStatus.REFUSED, reason);
    }

    public static ControlResult failed(String reason) {
        return new ControlResult(ControlStatus.FAILED, reason);
    }

    public boolean done() {
        return status == ControlStatus.DONE;
    }
}
