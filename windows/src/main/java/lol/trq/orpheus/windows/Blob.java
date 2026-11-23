/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.windows;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads what the native library returns: a sequence of records, little endian, each led by a kind byte. */
final class Blob {
    static final int KIND_AGE = 0;
    static final int KIND_SESSIONS = 1;
    static final int KIND_THUMBNAIL = 2;
    static final int KIND_RESULT = 3;
    static final int KIND_STATUS = 4;

    record Session(
            int id,
            String app,
            String title,
            String artist,
            String album,
            String albumArtist,
            int trackNumber,
            int kind,
            int status,
            long positionMillis,
            long durationMillis,
            long ageMillis,
            double rate,
            int shuffle,
            int repeat,
            int controls,
            long thumbnail) {}

    interface Handler {
        void sessions(long builtNanosAgo, List<Session> sessions);

        void thumbnail(long key, byte[] bytes);

        void result(long request, int status, String reason);

        void status(boolean ready, String reason);
    }

    private Blob() {}

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
                        if (count < 0 || count > 4096) {
                            return;
                        }
                        List<Session> sessions = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            sessions.add(new Session(
                                    in.getInt(),
                                    text(in),
                                    text(in),
                                    text(in),
                                    text(in),
                                    text(in),
                                    in.getInt(),
                                    in.get(),
                                    in.get(),
                                    in.getLong(),
                                    in.getLong(),
                                    in.getLong(),
                                    in.getDouble(),
                                    in.get(),
                                    in.get(),
                                    in.getInt(),
                                    in.getLong()));
                        }
                        handler.sessions(age, sessions);
                    }
                    case KIND_THUMBNAIL -> {
                        long key = in.getLong();
                        handler.thumbnail(key, bytes(in));
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

    private static byte[] bytes(ByteBuffer in) {
        int length = in.getInt();
        if (length < 0 || length > in.remaining()) {
            throw new BufferUnderflowException();
        }
        byte[] data = new byte[length];
        in.get(data);
        return data;
    }

    private static String text(ByteBuffer in) {
        return new String(bytes(in), StandardCharsets.UTF_8);
    }
}
