/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lol.trq.orpheus.internal.Text;

/**
 * The few tags lyric matching needs, read straight from an audio file: ID3v2 (MP3), Vorbis comments (FLAC) and MP4
 * items. Files are untrusted: every size is checked and anything unreadable gives {@link #NONE}.
 *
 * @param title the title, or empty
 * @param artist the artist, or empty
 * @param durationMillis the length, or -1
 * @param lyrics embedded lyrics, or {@link Lyrics#NONE}
 */
public record AudioTags(String title, String artist, long durationMillis, Lyrics lyrics) {
    public static final AudioTags NONE = new AudioTags("", "", -1, Lyrics.NONE);

    private static final int MAX_TAG_BYTES = 16 << 20;
    private static final int READ_BYTES = 64 << 10;
    private static final int MAX_TEXT_BYTES = Lrc.MAX_BYTES;

    public AudioTags {
        title = Text.clean(title);
        artist = Text.clean(artist);
    }

    public static AudioTags read(Path file) {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            byte[] head = read(channel, 0, 12);
            if (head.length < 12) {
                return NONE;
            }
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                long size = 10 + synchsafe(head, 6);
                byte[] next = read(channel, size, 4);
                if (next.length == 4 && next[0] == 'f' && next[1] == 'L' && next[2] == 'a' && next[3] == 'C') {
                    return flac(channel, size + 4);
                }
                return id3(channel, head);
            }
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                return flac(channel, 4);
            }
            if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                return mp4(channel);
            }
            return NONE;
        } catch (IOException | RuntimeException e) {
            return NONE;
        }
    }

    private static byte[] read(SeekableByteChannel channel, long position, int length) throws IOException {
        if (position < 0 || position >= channel.size()) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(length, channel.size() - position));
        channel.position(position);
        // small steps: the runtime keeps a native buffer as large as the largest read for as long as the thread lives
        int end = buffer.limit();
        while (buffer.position() < end) {
            buffer.limit(Math.min(end, buffer.position() + READ_BYTES));
            if (channel.read(buffer) < 0) {
                break;
            }
        }
        return buffer.array();
    }

    private static int synchsafe(byte[] b, int at) {
        return (b[at] & 0x7F) << 21 | (b[at + 1] & 0x7F) << 14 | (b[at + 2] & 0x7F) << 7 | (b[at + 3] & 0x7F);
    }

    private static int be32(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    private static Lyrics lyrics(String text) {
        return text == null || text.isBlank() ? Lyrics.NONE : Lrc.parse(text).lyrics();
    }

    private static Lyrics better(Lyrics a, Lyrics b) {
        return b.kind().ordinal() > a.kind().ordinal() ? b : a;
    }

    // ---- ID3v2

    private static AudioTags id3(SeekableByteChannel channel, byte[] head) throws IOException {
        int major = head[3] & 0xFF;
        int flags = head[5] & 0xFF;
        int size = synchsafe(head, 6);
        if (major < 2 || major > 4 || size > MAX_TAG_BYTES) {
            return NONE;
        }
        byte[] body = read(channel, 10, size);
        if ((flags & 0x80) != 0 && major < 4) {
            body = resync(body, body.length);
        }
        int pos = 0;
        if ((flags & 0x40) != 0 && major >= 3 && body.length >= 4) {
            pos = major == 4 ? synchsafe(body, 0) : 4 + be32(body, 0);
        }
        String title = "";
        String artist = "";
        long duration = -1;
        Lyrics found = Lyrics.NONE;
        int idLength = major == 2 ? 3 : 4;
        int headerLength = major == 2 ? 6 : 10;
        while (pos >= 0 && pos + headerLength <= body.length && body[pos] != 0) {
            String id = new String(body, pos, idLength, StandardCharsets.ISO_8859_1);
            int length = major == 2
                    ? (body[pos + 3] & 0xFF) << 16 | (body[pos + 4] & 0xFF) << 8 | (body[pos + 5] & 0xFF)
                    : major == 4 ? synchsafe(body, pos + 4) : be32(body, pos + 4);
            int frameFlags = major == 2 ? 0 : body[pos + 9] & 0xFF;
            int start = pos + headerLength;
            if (length <= 0 || start + length > body.length) {
                break;
            }
            pos = start + length;
            boolean packed = major == 4 ? (frameFlags & 0x0C) != 0 : (frameFlags & 0xC0) != 0;
            if (packed) {
                continue;
            }
            byte[] data = java.util.Arrays.copyOfRange(body, start, start + length);
            int dataLength = data.length;
            int skip = 0;
            if (major == 4 && (frameFlags & 0x01) != 0) {
                skip = 4;
            }
            if (major == 4 && (frameFlags & 0x02) != 0) {
                data = resync(data, dataLength);
                dataLength = data.length;
            }
            if (skip >= dataLength) {
                continue;
            }
            switch (id) {
                case "TIT2", "TT2" -> title = text(data, skip, dataLength);
                case "TPE1", "TP1" -> artist = text(data, skip, dataLength);
                case "TLEN", "TLE" -> duration = millis(text(data, skip, dataLength));
                case "USLT", "ULT" -> found = better(found, lyrics(unsynced(data, skip, dataLength)));
                case "SYLT", "SLT" -> found = better(found, synced(data, skip, dataLength));
                default -> {}
            }
        }
        return new AudioTags(title, artist, duration, found);
    }

    private static byte[] resync(byte[] data, int length) {
        byte[] out = new byte[length];
        int n = 0;
        for (int i = 0; i < length; i++) {
            out[n++] = data[i];
            if ((data[i] & 0xFF) == 0xFF && i + 1 < length && data[i + 1] == 0) {
                i++;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static long millis(String text) {
        try {
            long value = Long.parseLong(text.strip());
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Charset charset(int encoding) {
        return switch (encoding) {
            case 1 -> StandardCharsets.UTF_16;
            case 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> StandardCharsets.ISO_8859_1;
        };
    }

    /** The index just past the terminator of the string starting at {@code from}, or {@code end} when unterminated. */
    private static int terminator(byte[] data, int from, int end, int encoding) {
        if (encoding == 1 || encoding == 2) {
            for (int i = from; i + 1 < end; i += 2) {
                if (data[i] == 0 && data[i + 1] == 0) {
                    return i + 2;
                }
            }
            return end;
        }
        for (int i = from; i < end; i++) {
            if (data[i] == 0) {
                return i + 1;
            }
        }
        return end;
    }

    private static String string(byte[] data, int from, int to, int encoding) {
        int end = to;
        int width = encoding == 1 || encoding == 2 ? 2 : 1;
        while (end - width >= from && data[end - 1] == 0 && (width == 1 || data[end - 2] == 0)) {
            end -= width;
        }
        if (end <= from || end - from > MAX_TEXT_BYTES) {
            return "";
        }
        return new String(data, from, end - from, charset(encoding));
    }

    private static String text(byte[] data, int from, int end) {
        return string(data, from + 1, end, data[from] & 0xFF).strip();
    }

    private static String unsynced(byte[] data, int from, int end) {
        if (end - from < 5) {
            return "";
        }
        int encoding = data[from] & 0xFF;
        int body = terminator(data, from + 4, end, encoding);
        return string(data, body, end, encoding);
    }

    private static Lyrics synced(byte[] data, int from, int end) {
        if (end - from < 7 || data[from + 4] != 2) {
            return Lyrics.NONE;
        }
        int encoding = data[from] & 0xFF;
        int pos = terminator(data, from + 6, end, encoding);
        List<long[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        while (pos < end && texts.size() < Lrc.MAX_LINES) {
            int next = terminator(data, pos, end, encoding);
            if (next + 4 > end) {
                break;
            }
            texts.add(Text.clean(string(data, pos, next, encoding)));
            times.add(new long[] {be32(data, next) & 0xFFFFFFFFL});
            pos = next + 4;
        }
        if (texts.isEmpty()) {
            return Lyrics.NONE;
        }
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            long endTime = i + 1 < texts.size() ? times.get(i + 1)[0] : -1;
            lines.add(new LyricLine(times.get(i)[0], endTime, texts.get(i), List.of(), "", ""));
        }
        lines.sort((a, b) -> Long.compare(a.startMillis(), b.startMillis()));
        return new Lyrics(Lyrics.Kind.LINE, lines, 0, "", 0);
    }

    // ---- FLAC

    private static AudioTags flac(SeekableByteChannel channel, long start) throws IOException {
        long pos = start;
        String title = "";
        String artist = "";
        long duration = -1;
        Lyrics found = Lyrics.NONE;
        for (int blocks = 0; blocks < 64; blocks++) {
            byte[] header = read(channel, pos, 4);
            if (header.length < 4) {
                break;
            }
            int type = header[0] & 0x7F;
            int length = (header[1] & 0xFF) << 16 | (header[2] & 0xFF) << 8 | (header[3] & 0xFF);
            pos += 4;
            if (type == 0 && length >= 18) {
                byte[] info = read(channel, pos, 18);
                if (info.length == 18) {
                    long rate = (info[10] & 0xFFL) << 12 | (info[11] & 0xFFL) << 4 | (info[12] & 0xF0L) >> 4;
                    long samples = (info[13] & 0x0FL) << 32 | (be32(info, 14) & 0xFFFFFFFFL);
                    duration = rate > 0 && samples > 0 ? samples * 1000 / rate : -1;
                }
            } else if (type == 4 && length <= MAX_TAG_BYTES) {
                byte[] comments = read(channel, pos, length);
                int at = 0;
                if (comments.length >= 8) {
                    at = 4 + le32(comments, 0);
                    int count = at >= 0 && at + 4 <= comments.length ? le32(comments, at) : 0;
                    at += 4;
                    for (int i = 0; i < count && at >= 0 && at + 4 <= comments.length; i++) {
                        int size = le32(comments, at);
                        at += 4;
                        if (size < 0 || at + size > comments.length) {
                            break;
                        }
                        String entry = new String(comments, at, size, StandardCharsets.UTF_8);
                        at += size;
                        int eq = entry.indexOf('=');
                        if (eq <= 0) {
                            continue;
                        }
                        String value = entry.substring(eq + 1);
                        switch (entry.substring(0, eq).toUpperCase(Locale.ROOT)) {
                            case "TITLE" -> title = value.strip();
                            case "ARTIST" -> {
                                if (artist.length() < Text.MAX_LENGTH) {
                                    String name = Text.clean(value);
                                    artist = artist.isEmpty() ? name : artist + ", " + name;
                                }
                            }
                            case "LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS" -> found = better(found, lyrics(value));
                            default -> {}
                        }
                    }
                }
            }
            pos += length;
            if ((header[0] & 0x80) != 0) {
                break;
            }
        }
        return new AudioTags(title, artist, duration, found);
    }

    // ---- MP4

    private static AudioTags mp4(SeekableByteChannel channel) throws IOException {
        String[] text = {"", "", ""};
        long[] duration = {-1};
        atoms(channel, 0, channel.size(), 0, text, duration);
        return new AudioTags(text[0], text[1], duration[0], lyrics(text[2]));
    }

    private static void atoms(
            SeekableByteChannel channel, long from, long to, int depth, String[] text, long[] duration)
            throws IOException {
        long pos = from;
        for (int count = 0; count < 4096 && pos + 8 <= to; count++) {
            byte[] header = read(channel, pos, 16);
            if (header.length < 8) {
                return;
            }
            long size = be32(header, 0) & 0xFFFFFFFFL;
            int headerSize = 8;
            if (size == 1 && header.length == 16) {
                size = (long) be32(header, 8) << 32 | (be32(header, 12) & 0xFFFFFFFFL);
                headerSize = 16;
            } else if (size == 0) {
                size = to - pos;
            }
            if (size < headerSize || pos + size > to) {
                return;
            }
            String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            long body = pos + headerSize;
            long end = pos + size;
            switch (type) {
                case "moov", "udta", "ilst" -> {
                    if (depth < 8) {
                        atoms(channel, body, end, depth + 1, text, duration);
                    }
                }
                case "meta" -> {
                    if (depth < 8) {
                        atoms(channel, body + 4, end, depth + 1, text, duration);
                    }
                }
                case "mvhd" -> {
                    byte[] data = read(channel, body, 32);
                    if (data.length == 32) {
                        boolean wide = data[0] == 1;
                        long scale = be32(data, wide ? 20 : 12) & 0xFFFFFFFFL;
                        long length = wide
                                ? (long) be32(data, 24) << 32 | (be32(data, 28) & 0xFFFFFFFFL)
                                : be32(data, 16) & 0xFFFFFFFFL;
                        duration[0] = scale > 0 && length > 0 ? length * 1000 / scale : -1;
                    }
                }
                case "©nam" -> text[0] = item(channel, body, end).strip();
                case "©ART" -> text[1] = item(channel, body, end).strip();
                case "©lyr" -> text[2] = item(channel, body, end);
                default -> {}
            }
            pos = end;
        }
    }

    private static String item(SeekableByteChannel channel, long from, long to) throws IOException {
        byte[] header = read(channel, from, 16);
        if (header.length < 16 || header[4] != 'd' || header[5] != 'a' || header[6] != 't' || header[7] != 'a') {
            return "";
        }
        long size = (be32(header, 0) & 0xFFFFFFFFL) - 16;
        if (size <= 0 || size > MAX_TEXT_BYTES || from + 16 + size > to) {
            return "";
        }
        return new String(read(channel, from + 16, (int) size), StandardCharsets.UTF_8);
    }
}
