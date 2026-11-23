/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/** What a session says it is playing. Browsers report {@link #MUSIC} for everything, so treat it as a hint. */
public enum MediaKind {
    MUSIC,
    VIDEO,
    OTHER,
    UNKNOWN
}
