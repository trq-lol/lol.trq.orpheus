/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.testkit;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Generated media for tests: images and tagged audio containers. No real music is ever used. */
public final class Media {
    private Media() {}

    public static byte[] png(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] be32(long v) {
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] le32(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] synchsafe(int v) {
        return new byte[] {(byte) (v >> 21 & 0x7F), (byte) (v >> 14 & 0x7F), (byte) (v >> 7 & 0x7F), (byte) (v & 0x7F)};
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- ID3v2

    public static byte[] id3Frame(int major, String id, byte[] body) {
        return concat(
                id.getBytes(StandardCharsets.ISO_8859_1),
                major == 4 ? synchsafe(body.length) : be32(body.length),
                new byte[2],
                body);
    }

    public static byte[] id3Text(int major, String id, String text) {
        return id3Frame(major, id, concat(new byte[] {3}, utf8(text)));
    }

    /** A text frame in UTF-16 with a byte order mark, as many taggers write. */
    public static byte[] id3Utf16Text(int major, String id, String text) {
        return id3Frame(major, id, concat(new byte[] {1}, text.getBytes(StandardCharsets.UTF_16)));
    }

    public static byte[] id3Unsynced(int major, String lyrics) {
        return id3Frame(major, "USLT", concat(new byte[] {3, 'e', 'n', 'g', 0}, utf8(lyrics)));
    }

    public static byte[] id3Synced(int major, Object... timeAndText) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[] {3, 'e', 'n', 'g', 2, 1, 0});
        for (int i = 0; i < timeAndText.length; i += 2) {
            body.writeBytes(utf8((String) timeAndText[i + 1]));
            body.write(0);
            body.writeBytes(be32((Integer) timeAndText[i]));
        }
        return id3Frame(major, "SYLT", body.toByteArray());
    }

    public static byte[] id3(int major, byte[]... frames) {
        byte[] body = concat(concat(frames), new byte[64]);
        return concat(new byte[] {'I', 'D', '3', (byte) major, 0, 0}, synchsafe(body.length), body);
    }

    // ---- FLAC

    public static byte[] flac(int sampleRate, long samples, String... comments) {
        byte[] info = new byte[34];
        info[10] = (byte) (sampleRate >> 12);
        info[11] = (byte) (sampleRate >> 4);
        info[12] = (byte) ((sampleRate & 0xF) << 4 | 0x02);
        info[13] = (byte) (0x70 | (samples >> 32 & 0xF));
        System.arraycopy(be32(samples), 0, info, 14, 4);
        ByteArrayOutputStream vorbis = new ByteArrayOutputStream();
        vorbis.writeBytes(le32(4));
        vorbis.writeBytes(utf8("test"));
        vorbis.writeBytes(le32(comments.length));
        for (String comment : comments) {
            vorbis.writeBytes(le32(utf8(comment).length));
            vorbis.writeBytes(utf8(comment));
        }
        byte[] v = vorbis.toByteArray();
        return concat(
                utf8("fLaC"),
                new byte[] {0, 0, 0, 34},
                info,
                new byte[] {(byte) 0x84, (byte) (v.length >> 16), (byte) (v.length >> 8), (byte) v.length},
                v,
                new byte[32]);
    }

    // ---- MP4

    public static byte[] atom(String type, byte[]... children) {
        byte[] body = concat(children);
        return concat(be32(body.length + 8), type.getBytes(StandardCharsets.ISO_8859_1), body);
    }

    private static byte[] mp4Item(String type, String value) {
        return atom(type, atom("data", be32(1), be32(0), utf8(value)));
    }

    public static byte[] mp4(String title, String artist, String lyrics, int seconds) {
        byte[] mvhd = new byte[100];
        System.arraycopy(be32(1000), 0, mvhd, 12, 4);
        System.arraycopy(be32(seconds * 1000L), 0, mvhd, 16, 4);
        return concat(
                atom("ftyp", utf8("M4A "), new byte[4]),
                atom(
                        "moov",
                        atom("mvhd", mvhd),
                        atom(
                                "udta",
                                atom(
                                        "meta",
                                        new byte[4],
                                        atom(
                                                "ilst",
                                                mp4Item("©nam", title),
                                                mp4Item("©ART", artist),
                                                mp4Item("©lyr", lyrics))))),
                atom("mdat", new byte[16]));
    }
}
