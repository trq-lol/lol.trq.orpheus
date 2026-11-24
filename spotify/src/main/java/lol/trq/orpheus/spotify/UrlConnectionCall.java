/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.spotify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The default {@link HttpCall}. Follows no redirects, so the token never leaves the host it was meant for. */
final class UrlConnectionCall implements HttpCall {
    private static final int CONNECT_MILLIS = 3000;
    private static final int READ_MILLIS = 4000;
    private static final long TOTAL_NANOS = 8_000_000_000L;

    private final Set<HttpURLConnection> live = ConcurrentHashMap.newKeySet();

    @Override
    public void abort() {
        live.forEach(HttpURLConnection::disconnect);
    }

    @Override
    public Answer send(String method, String url, Map<String, String> headers, byte[] body, int maxBytes)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        live.add(connection);
        try {
            return exchange(connection, method, headers, body, maxBytes);
        } finally {
            live.remove(connection);
        }
    }

    private static Answer exchange(
            HttpURLConnection connection, String method, Map<String, String> headers, byte[] body, int maxBytes)
            throws IOException {
        long started = System.nanoTime();
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
                answer = read(in, maxBytes, started);
            } catch (IOException e) {
                connection.disconnect();
                throw e;
            }
        }
        return new Answer(status, seconds(connection.getHeaderField("Retry-After")), answer);
    }

    private static byte[] read(InputStream in, int maxBytes, long started) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            out.write(buffer, 0, n);
            if (out.size() > maxBytes) {
                throw new IOException("the answer is longer than " + maxBytes + " bytes");
            }
            if (System.nanoTime() - started > TOTAL_NANOS) {
                throw new IOException("the answer took too long");
            }
        }
        return out.toByteArray();
    }

    private static long seconds(String value) {
        try {
            return value == null ? -1 : Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
