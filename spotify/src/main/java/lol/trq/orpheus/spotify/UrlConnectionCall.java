/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.spotify;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** The default {@link HttpCall}. Follows no redirects, so the token never leaves the host it was meant for. */
final class UrlConnectionCall implements HttpCall {
    private static final int CONNECT_MILLIS = 3000;
    private static final int READ_MILLIS = 4000;

    @Override
    public Answer send(String method, String url, Map<String, String> headers, byte[] body, int maxBytes)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_MILLIS);
        connection.setReadTimeout(READ_MILLIS);
        connection.setRequestMethod(method);
        headers.forEach(connection::setRequestProperty);
        if (!method.equals("GET")) {
            byte[] content = body == null ? new byte[0] : body;
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(content.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(content);
            }
        }
        int status = connection.getResponseCode();
        byte[] answer = new byte[0];
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (in != null) {
            try (in) {
                answer = in.readNBytes(maxBytes + 1);
            }
        }
        if (answer.length > maxBytes) {
            connection.disconnect();
            throw new IOException("the answer is longer than " + maxBytes + " bytes");
        }
        return new Answer(status, seconds(connection.getHeaderField("Retry-After")), answer);
    }

    private static long seconds(String value) {
        try {
            return value == null ? -1 : Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
