/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.spotify;

import java.io.IOException;
import java.util.Map;

/** Sends one HTTP request and waits for the answer. Replace it to use your own client. */
@FunctionalInterface
public interface HttpCall {
    /**
     * @param method GET, PUT, POST or DELETE
     * @param url an https address
     * @param headers the request headers, including the authorization
     * @param body the request body, or null for none
     * @param maxBytes the longest answer to accept; a longer one is an {@link IOException}
     */
    Answer send(String method, String url, Map<String, String> headers, byte[] body, int maxBytes) throws IOException;

    /**
     * @param status the HTTP status
     * @param retryAfterSeconds the {@code Retry-After} header, or -1
     * @param body the body, possibly empty
     */
    record Answer(int status, long retryAfterSeconds, byte[] body) {}
}
