/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/** How a command ended. */
public enum ControlStatus {
    /** The player accepted the command. It may still be acting on it. */
    DONE,
    /** The selected session cannot do this, or nothing is selected. */
    UNSUPPORTED,
    /** The player was asked and said no. */
    REFUSED,
    /** The command could not be delivered, or timed out. */
    FAILED
}
