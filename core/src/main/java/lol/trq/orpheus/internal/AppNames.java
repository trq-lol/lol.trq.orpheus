/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import java.util.Locale;

/** Turns the ids systems give applications into names a person can read. Not API. */
public final class AppNames {
    private AppNames() {}

    /**
     * A name for a Windows application id: an executable ({@code Spotify.exe}), a packaged app user model id
     * ({@code SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify}) or a dotted id ({@code Microsoft.Media.Player}).
     */
    public static String windows(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String trimmed = id.strip();
        int bang = trimmed.indexOf('!');
        if (bang > 0) {
            String family = trimmed.substring(0, bang);
            int underscore = family.indexOf('_');
            String app = underscore > 0 ? family.substring(0, underscore) : family;
            int dot = app.indexOf('.');
            if (dot >= 0 && dot < app.length() - 1) {
                String name = app.substring(dot + 1);
                int next = name.indexOf('.');
                return humanize(next > 0 ? name.substring(0, next) : name);
            }
            return humanize(app);
        }
        String stem = trimmed.toLowerCase(Locale.ROOT).endsWith(".exe")
                ? trimmed.substring(0, trimmed.length() - 4)
                : trimmed;
        int slash = Math.max(stem.lastIndexOf('\\'), stem.lastIndexOf('/'));
        stem = stem.substring(slash + 1);
        int dot = stem.lastIndexOf('.');
        return humanize(dot >= 0 && dot < stem.length() - 1 ? stem.substring(dot + 1) : stem);
    }

    /** Splits camel case into words and capitalizes the first letter: {@code SpotifyMusic} becomes "Spotify Music". */
    public static String humanize(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(name.length() + 4);
        boolean previousLower = false;
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            if (Character.isUpperCase(c) && previousLower) {
                out.append(' ');
            }
            out.appendCodePoint(i == 0 ? Character.toUpperCase(c) : c);
            previousLower = Character.isLowerCase(c);
            i += Character.charCount(c);
        }
        return out.toString();
    }
}
