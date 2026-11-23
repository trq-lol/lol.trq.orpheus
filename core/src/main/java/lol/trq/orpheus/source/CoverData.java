/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

/**
 * An encoded cover image as a source received it. Keep passing the same instance while the image is unchanged.
 *
 * @param key identifies the image; equal keys mean equal images
 * @param bytes PNG, JPEG, BMP or GIF data; not modified by the library
 */
public record CoverData(String key, byte[] bytes) {
    /** Wraps image bytes under a key derived from their content. */
    public static CoverData of(byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash = (hash ^ (b & 0xFF)) * 0x100000001b3L;
        }
        return new CoverData(Long.toHexString(hash) + "-" + bytes.length, bytes);
    }
}
