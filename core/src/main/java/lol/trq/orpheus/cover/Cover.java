/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.cover;

/**
 * A decoded cover image.
 *
 * @param key identifies the image; the same key always means the same pixels
 * @param width width in pixels
 * @param height height in pixels
 * @param rgba four bytes per pixel in R, G, B, A order, rows top to bottom, alpha not premultiplied. This is the
 *     cached array: do not modify it
 * @param average the average colour as 0xRRGGBB
 * @param palette up to five dominant colours as 0xRRGGBB, most common first. Do not modify
 */
public record Cover(String key, int width, int height, byte[] rgba, int average, int[] palette) {
    int bytes() {
        return rgba.length + 64;
    }
}
