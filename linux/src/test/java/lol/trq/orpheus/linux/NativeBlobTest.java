/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeBlobTest {
    private static final class Collector implements NativeBlob.Handler {
        final List<NativeBlob.Session> sessions = new ArrayList<>();
        long age = -1;
        final List<String> results = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();

        @Override
        public void sessions(long builtNanosAgo, List<NativeBlob.Session> list) {
            age = builtNanosAgo;
            sessions.addAll(list);
        }

        @Override
        public void result(long request, int status, String reason) {
            results.add(request + ":" + status + ":" + reason);
        }

        @Override
        public void status(boolean ready, String reason) {
            statuses.add(ready + ":" + reason);
        }
    }

    private static void text(ByteBuffer out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.putInt(bytes.length).put(bytes);
    }

    private static byte[] blob() {
        ByteBuffer out = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) NativeBlob.KIND_STATUS).put((byte) 1);
        text(out, "");
        out.put((byte) NativeBlob.KIND_AGE).putLong(1234);
        out.put((byte) NativeBlob.KIND_SESSIONS).putInt(1);
        out.putInt(7);
        text(out, "org.mpris.MediaPlayer2.spotify");
        text(out, "spotify");
        text(out, "Spotify");
        text(out, "Song");
        out.putInt(2);
        text(out, "One");
        text(out, "Two");
        text(out, "Album");
        text(out, "One, Two");
        out.putInt(3);
        text(out, "");
        text(out, "/track/1");
        text(out, "file:///cover.png");
        out.put((byte) 1).putLong(42_000).putLong(180_000).putLong(5_000);
        out.putDouble(1.0).put((byte) 1).put((byte) 2).putDouble(0.5).putInt(0b111);
        out.put((byte) NativeBlob.KIND_RESULT).putLong(9).put((byte) 3);
        text(out, "no");
        return Arrays.copyOf(out.array(), out.position());
    }

    @Test
    void readsEveryRecord() {
        Collector c = new Collector();
        NativeBlob.read(blob(), c);
        assertEquals(List.of("true:"), c.statuses);
        assertEquals(1234, c.age);
        assertEquals(1, c.sessions.size());
        NativeBlob.Session s = c.sessions.get(0);
        assertEquals(7, s.id());
        assertEquals("org.mpris.MediaPlayer2.spotify", s.name());
        assertEquals(List.of("One", "Two"), s.artists());
        assertEquals("/track/1", s.trackId());
        assertEquals(42_000, s.positionMillis());
        assertEquals(180_000, s.lengthMillis());
        assertEquals(5_000, s.ageNanos());
        assertEquals(2, s.repeat());
        assertEquals(0.5, s.volume());
        assertEquals(0b111, s.controls());
        assertEquals(List.of("9:3:no"), c.results);
    }

    @Test
    void truncatedBlobsStopQuietly() {
        byte[] full = blob();
        for (int length = 0; length < full.length; length++) {
            Collector c = new Collector();
            NativeBlob.read(Arrays.copyOf(full, length), c);
            assertTrue(c.sessions.size() <= 1);
        }
    }
}
