/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/**
 * Rules a source attaches to its data. Operating system sessions have none; a music service usually has some.
 *
 * @param artworkUnmodified the cover must be shown uncropped, without overlays or heavy rounding
 * @param attribution text that must be shown next to the data, or null
 * @param linkBack a link the display must offer, or null
 */
public record DisplayRules(boolean artworkUnmodified, String attribution, String linkBack) {
    public static final DisplayRules NONE = new DisplayRules(false, null, null);
}
