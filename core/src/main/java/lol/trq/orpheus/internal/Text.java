/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text clean-up shared by song identity and lyric matching. Not API. */
public final class Text {
    public static final int MAX_LENGTH = 1024;

    private static final String VERSIONS = "live|remix|acoustic|instrumental|demo|karaoke|cover";
    private static final String NOISE = "feat|ft|featuring|with|remaster|remastered|version|edit|mix|mono|stereo|deluxe"
            + "|bonus|explicit|clean|single|album|original|radio|from|prod|" + VERSIONS;
    private static final Pattern BRACKETED = Pattern.compile("[(\\[{][^)\\]}]*\\b(?:" + NOISE + ")\\b[^)\\]}]*[)\\]}]");
    private static final Pattern DASH_SUFFIX =
            Pattern.compile("\\s+[-\u2013\u2014]\\s+[^-\u2013\u2014]*\\b(?:" + NOISE + ")\\b.*$");
    private static final Pattern FEATURING = Pattern.compile("\\s+(?:feat|ft|featuring)\\b\\.?\\s.*$");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern VERSION = Pattern.compile("\\b(?:" + VERSIONS + ")\\b");
    private static final Pattern ARTIST_SPLIT =
            Pattern.compile("\\s*(?:[,;&/]|\\s+x\\s+|\\s+and\\s+|\\b(?:feat|ft|featuring)\\b\\.?)\\s*");

    private Text() {}

    /** Trims, drops control characters and caps the length of text that came from outside. */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        String text = value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                if (out == null) {
                    out = new StringBuilder(text.length()).append(text, 0, i);
                }
                out.append(' ');
            } else if (out != null) {
                out.append(c);
            }
        }
        return (out == null ? text : out.toString()).trim();
    }

    /** A comparison key for a title: case, width, punctuation and "feat"/"remaster" decorations removed. */
    public static String title(String value) {
        String text = fold(value);
        text = BRACKETED.matcher(text).replaceAll(" ");
        text = DASH_SUFFIX.matcher(text).replaceAll("");
        text = FEATURING.matcher(text).replaceAll("");
        String key = squash(text);
        return key.isEmpty() ? squash(fold(value)) : key;
    }

    /** The version markers in a title ("live", "remix", ...), sorted and joined; empty for a plain title. */
    public static String versions(String value) {
        List<String> found = new ArrayList<>();
        Matcher m = VERSION.matcher(fold(value));
        while (m.find()) {
            if (!found.contains(m.group())) {
                found.add(m.group());
            }
        }
        found.sort(null);
        return String.join(" ", found);
    }

    /** Comparison keys for the artists in one or more credit strings ("A, B and C feat. D" gives four). */
    public static List<String> artists(List<String> credits) {
        List<String> keys = new ArrayList<>();
        for (String credit : credits) {
            for (String part : ARTIST_SPLIT.split(fold(credit))) {
                String key = squash(part);
                if (key.startsWith("the ")) {
                    key = key.substring(4);
                }
                if (!key.isEmpty() && !keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /** A comparison key for a whole credit string. */
    public static String artist(String credit) {
        return squash(fold(credit));
    }

    /** Whether two credit lists name at least one artist in common. */
    public static boolean shareArtist(List<String> a, List<String> b) {
        List<String> left = artists(a);
        for (String key : artists(b)) {
            if (left.contains(key)) {
                return true;
            }
        }
        return squash(fold(String.join(" ", a))).equals(squash(fold(String.join(" ", b))));
    }

    private static String fold(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static String squash(String text) {
        return NOT_WORD.matcher(text).replaceAll(" ").trim();
    }
}
