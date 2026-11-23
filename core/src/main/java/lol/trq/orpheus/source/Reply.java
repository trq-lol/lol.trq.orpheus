/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import lol.trq.orpheus.ControlResult;

/**
 * The answer to a question asked of a source.
 *
 * @param result how the request ended
 * @param value the answer when {@code result} is done; otherwise an empty value, never null
 * @param <T> the answer type
 */
public record Reply<T>(ControlResult result, T value) {
    public static <T> Reply<T> of(T value) {
        return new Reply<>(ControlResult.DONE, value);
    }

    public boolean done() {
        return result.done();
    }
}
