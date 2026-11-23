/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.cover;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import lol.trq.orpheus.testkit.Media;
import org.junit.jupiter.api.Test;

class CoverTest {
    @Test
    void decodesToRgbaBytesWithTheRightColour() {
        Cover cover = CoverDecoder.decode("k", Media.png(4, 2, 0xFF2040C0), 512);
        assertEquals(4, cover.width());
        assertEquals(2, cover.height());
        assertArrayEquals(new byte[] {0x20, 0x40, (byte) 0xC0, (byte) 0xFF}, Arrays.copyOf(cover.rgba(), 4));
        assertEquals(0x2040C0, cover.average());
        assertEquals(0x2040C0, cover.palette()[0]);
    }

    @Test
    void largeImagesAreScaledToTheLimitKeepingTheirShape() {
        Cover cover = CoverDecoder.decode("k", Media.png(1000, 500, 0xFF808080), 128);
        assertTrue(Math.max(cover.width(), cover.height()) <= 128);
        assertEquals(2.0, (double) cover.width() / cover.height(), 0.1);
        assertEquals(cover.width() * cover.height() * 4, cover.rgba().length);
    }

    @Test
    void hostileImageDataGivesNullNotAnException() {
        assertNull(CoverDecoder.decode("k", null, 512));
        assertNull(CoverDecoder.decode("k", new byte[0], 512));
        assertNull(CoverDecoder.decode("k", "not an image at all".getBytes(), 512));
        byte[] png = Media.png(8, 8, 0xFF112233);
        byte[] huge = png.clone();
        huge[16] = 0x7F; // claims a width of two thousand million pixels
        assertNull(CoverDecoder.decode("k", huge, 512));
        Random random = new Random(3);
        for (int cut = 0; cut < png.length; cut += 3) {
            CoverDecoder.decode("k", Arrays.copyOf(png, cut), 512);
        }
        for (int i = 0; i < 300; i++) {
            byte[] broken = png.clone();
            broken[random.nextInt(broken.length)] = (byte) random.nextInt(256);
            CoverDecoder.decode("k", broken, 512);
        }
    }

    @Test
    void paletteSeparatesDominantColours() {
        byte[] rgba = new byte[32 * 32 * 4];
        for (int i = 0; i < 32 * 32; i++) {
            boolean red = i % 32 < 24;
            rgba[i * 4] = (byte) (red ? 250 : 10);
            rgba[i * 4 + 1] = 10;
            rgba[i * 4 + 2] = (byte) (red ? 10 : 250);
            rgba[i * 4 + 3] = (byte) 255;
        }
        int[] palette = Palette.dominant(Palette.sample(rgba, 32, 32), 2);
        assertEquals(0xFA0A0A, palette[0], "three quarters red comes first");
        assertEquals(0x0A0AFA, palette[1]);
        assertEquals(0, Palette.dominant(new int[0], 5).length, "a fully transparent image has no palette");
    }

    @Test
    void theCacheStaysWithinItsLimitAndDropsTheLeastRecentlyUsed() {
        CoverCache cache = new CoverCache(3 * (4000 + 64));
        for (String key : new String[] {"a", "b", "c"}) {
            cache.put(new Cover(key, 10, 100, new byte[4000], 0, new int[0]));
        }
        assertNotNull(cache.get("a"));
        cache.put(new Cover("d", 10, 100, new byte[4000], 0, new int[0]));
        assertNull(cache.get("b"), "b was the least recently used");
        assertNotNull(cache.get("a"));
        assertNotNull(cache.get("d"));
        assertTrue(cache.bytes() <= 3 * (4000 + 64));

        cache.put(new Cover("giant", 100, 100, new byte[40_000], 0, new int[0]));
        assertNull(cache.get("giant"), "one cover bigger than the cache is not kept");
        cache.put(new Cover("a", 10, 100, new byte[4000], 0, new int[0]));
        assertEquals(3, cache.size(), "replacing a key must not count its bytes twice");
        assertTrue(cache.bytes() <= 3 * (4000 + 64));
    }
}
