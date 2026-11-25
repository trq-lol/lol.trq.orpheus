/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.util.List;

/**
 * A stand-in media player the harness controls: it publishes generated tracks to the operating system and records
 * the commands the system delivers back.
 */
interface Publisher extends AutoCloseable {
    /** The rule that makes this player visible in {@code AppRules}. */
    String appRule();

    void track(String title, String artist, String album, long durationMillis, Integer coverRgb, boolean video)
            throws Exception;

    void state(String state) throws Exception;

    void position(long millis) throws Exception;

    /** Turns next, previous, fast-forward and rewind on or off. */
    void controls(boolean all) throws Exception;

    /** Whether {@link #hold} works. */
    default boolean canHold() {
        return false;
    }

    /** While on, changes are kept back; off publishes them as one update. */
    default void hold(boolean on) throws Exception {
        throw new UnsupportedOperationException("hold");
    }

    /** Waits for the next command the player receives, or returns null after the timeout. */
    String awaitCommand(long timeoutMillis) throws InterruptedException;

    List<String> commands();

    /** Ends the player abruptly, as a crash would. */
    void kill();

    /** Which controls this kind of publisher can receive at all, by harness name. */
    List<String> supportedCommands();

    @Override
    void close();
}
