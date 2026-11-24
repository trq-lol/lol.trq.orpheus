/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.spotify;

import java.util.concurrent.CompletableFuture;

/**
 * Supplies access tokens. The library never signs in and never sees a refresh token or a client secret.
 *
 * <p>Return the token you have while it is valid and refresh it when it is about to expire. Return null, or complete
 * with null, while the user is not signed in.
 */
@FunctionalInterface
public interface TokenSource {
    /** A valid access token, or null when there is none. Called on a library thread, one call at a time. */
    CompletableFuture<String> accessToken();

    /** The service no longer accepts {@code token}: the next {@link #accessToken()} should return a new one. */
    default void rejected(String token) {}
}
