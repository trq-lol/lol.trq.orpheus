/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/** A registered listener. Closing it stops delivery; closing twice is harmless. */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
