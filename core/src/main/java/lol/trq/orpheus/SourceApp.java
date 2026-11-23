/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/**
 * The application behind a session.
 *
 * @param id a stable identifier: an executable or app model id on Windows, a desktop entry or bus name on Linux, a
 *     bundle id on macOS
 * @param name a display name, or the id when the source has none
 */
public record SourceApp(String id, String name) {
    public static final SourceApp UNKNOWN = new SourceApp("", "");
}
