/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads what the native library returns: a sequence of records, little endian, each led by a kind byte. */
final class NativeBlob {
    static final int KIND_AGE = 0;
    static final int KIND_SESSIONS = 1;
    static final int KIND_RESULT = 3;
    static final int KIND_STATUS = 4;

    private static final int MAX_SESSIONS = 4096;
    private static final int MAX_ARTISTS = 64;

    record Session(
            int id,
            String name,
            String app,
            String identity,
            String title,
            List<String> artists,
            String album,
            String albumArtist,
            int trackNumber,
            String url,
            String trackId,
            String artUrl,
            int status,
            long positionMillis,
            long lengthMillis,
            long ageNanos,
            double rate,
            int shuffle,
            int repeat,
            double volume,
            int controls) {}

    interface Handler {
        void sessions(long builtNanosAgo, List<Session> sessions);

        void result(long request, int status, String reason);

        void status(boolean ready, String reason);
    }

    private NativeBlob() {}

    /** Feeds every record to {@code handler}. Stops quietly at the first record that does not parse. */
    static void read(byte[] blob, Handler handler) {
        ByteBuffer in = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        long age = 0;
        try {
            while (in.hasRemaining()) {
                int kind = in.get();
                switch (kind) {
                    case KIND_AGE -> age = in.getLong();
                    case KIND_SESSIONS -> {
                        int count = in.getInt();
                        if (count < 0 || count > MAX_SESSIONS) {
                            return;
                        }
                        List<Session> sessions = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            sessions.add(session(in));
                        }
                        handler.sessions(age, sessions);
                    }
                    case KIND_RESULT -> {
                        long request = in.getLong();
                        int status = in.get();
                        handler.result(request, status, text(in));
                    }
                    case KIND_STATUS -> {
                        boolean ready = in.get() == 1;
                        handler.status(ready, text(in));
                    }
                    default -> {
                        return;
                    }
                }
            }
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            // a truncated blob: what was read so far has been delivered
        }
    }

    private static Session session(ByteBuffer in) {
        int id = in.getInt();
        String name = text(in);
        String app = text(in);
        String identity = text(in);
        String title = text(in);
        int artistCount = in.getInt();
        if (artistCount < 0 || artistCount > MAX_ARTISTS) {
            throw new BufferUnderflowException();
        }
        List<String> artists = new ArrayList<>(artistCount);
        for (int i = 0; i < artistCount; i++) {
            artists.add(text(in));
        }
        return new Session(
                id,
                name,
                app,
                identity,
                title,
                List.copyOf(artists),
                text(in),
                text(in),
                in.getInt(),
                text(in),
                text(in),
                text(in),
                in.get(),
                in.getLong(),
                in.getLong(),
                in.getLong(),
                in.getDouble(),
                in.get(),
                in.get(),
                in.getDouble(),
                in.getInt());
    }

    private static String text(ByteBuffer in) {
        int length = in.getInt();
        if (length < 0 || length > in.remaining()) {
            throw new BufferUnderflowException();
        }
        String value = new String(in.array(), in.arrayOffset() + in.position(), length, StandardCharsets.UTF_8);
        in.position(in.position() + length);
        return value;
    }
}
