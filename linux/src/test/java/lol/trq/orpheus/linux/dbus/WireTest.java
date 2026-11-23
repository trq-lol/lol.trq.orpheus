/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.dbus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WireTest {
    private static byte[] hex(String text) {
        String clean = text.replace(" ", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    void matchesBytesWrittenByTheReferenceImplementation() throws Exception {
        // "ssv" body of Properties.Set(Player, "Shuffle", true), as captured from a real bus
        byte[] expected = hex("1d000000 6f72672e6d707269732e4d65646961506c61796572322e506c6179657200 0000"
                + "07000000 53687566666c6500 01 6200 00 01000000");
        byte[] written = new Wire.Writer()
                .body("ssv", "org.mpris.MediaPlayer2.Player", "Shuffle", new Variant("b", true))
                .bytes();
        assertArrayEquals(expected, written);
        assertEquals(
                List.of("org.mpris.MediaPlayer2.Player", "Shuffle", true),
                new Wire.Reader(expected, 0, ByteOrder.LITTLE_ENDIAN).body("ssv"));
    }

    @Test
    void nestedContainersSurviveARoundTripWithCorrectPadding() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mpris:trackid", new Variant("o", "/track/1"));
        metadata.put("mpris:length", new Variant("x", 180_000_000L));
        metadata.put("xesam:artist", new Variant("as", List.of("One", "Two")));
        metadata.put("xesam:title", new Variant("s", "東京 مرحبا"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("Metadata", new Variant("a{sv}", metadata));
        properties.put("Rate", new Variant("d", 1.5));
        properties.put("Empty", new Variant("as", List.of()));
        properties.put("Pair", new Variant("(yx)", List.of(7, -3L)));
        for (int lead = 0; lead < 8; lead++) {
            Wire.Writer writer = new Wire.Writer();
            for (int i = 0; i < lead; i++) {
                writer.write("y", 0);
            }
            byte[] data = writer.body("a{sv}as", properties, List.of("x")).bytes();
            Wire.Reader reader = new Wire.Reader(data, lead, ByteOrder.LITTLE_ENDIAN);
            @SuppressWarnings("unchecked")
            Map<String, Object> back = (Map<String, Object>) reader.read("a{sv}");
            Map<?, ?> meta = (Map<?, ?>) back.get("Metadata");
            assertEquals("/track/1", meta.get("mpris:trackid"));
            assertEquals(180_000_000L, meta.get("mpris:length"));
            assertEquals(List.of("One", "Two"), meta.get("xesam:artist"));
            assertEquals("東京 مرحبا", meta.get("xesam:title"));
            assertEquals(1.5, back.get("Rate"));
            assertEquals(List.of(), back.get("Empty"));
            assertEquals(List.of(7, -3L), back.get("Pair"));
            assertEquals(List.of("x"), reader.read("as"));
            assertEquals(data.length, reader.position());
        }
    }

    @Test
    void readsBigEndianSenders() throws Exception {
        byte[] data = hex("00000003 666f6f00 0000000000000005");
        assertEquals(List.of("foo", 5L), new Wire.Reader(data, 0, ByteOrder.BIG_ENDIAN).body("sx"));
    }

    @Test
    void hostileLengthsAndSignaturesAreRejectedCleanly() {
        assertThrows(
                WireException.class, () -> new Wire.Reader(hex("ffffff7f 41"), 0, ByteOrder.LITTLE_ENDIAN).read("s"));
        assertThrows(
                WireException.class,
                () -> new Wire.Reader(hex("ffffff7f 00000000"), 0, ByteOrder.LITTLE_ENDIAN).read("as"));
        assertThrows(
                WireException.class, () -> new Wire.Reader(hex("04000000"), 0, ByteOrder.LITTLE_ENDIAN).read("ax"));
        assertThrows(WireException.class, () -> new Wire.Reader(new byte[64], 0, ByteOrder.LITTLE_ENDIAN).read("a{s"));
        assertThrows(WireException.class, () -> new Wire.Reader(new byte[64], 0, ByteOrder.LITTLE_ENDIAN).read("z"));
        assertThrows(
                WireException.class, () -> new Wire.Reader(hex("02 7373 00 00"), 0, ByteOrder.LITTLE_ENDIAN).read("v"));
        byte[] deep = new byte[400];
        for (int i = 0; i + 3 < deep.length; i += 3) {
            deep[i] = 1;
            deep[i + 1] = 'v';
        }
        assertThrows(WireException.class, () -> new Wire.Reader(deep, 0, ByteOrder.LITTLE_ENDIAN).read("v"));
        assertThrows(WireException.class, () -> new Wire.Writer().write("s", 5));
        assertThrows(WireException.class, () -> new Wire.Writer().body("ss", "only one"));
    }

    @Test
    void randomBytesOnlyEverGiveAWireException() {
        Random random = new Random(5);
        String[] signatures = {"a{sv}", "sa{sv}as", "v", "a(yv)", "aav", "a{sa{sv}}", "(sxd)ag"};
        for (int i = 0; i < 20_000; i++) {
            byte[] data = new byte[random.nextInt(96)];
            random.nextBytes(data);
            String signature = signatures[i % signatures.length];
            try {
                new Wire.Reader(data, 0, i % 2 == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).body(signature);
            } catch (WireException expected) {
                // the only acceptable failure
            }
        }
    }
}
