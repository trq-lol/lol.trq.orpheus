/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which sessions of other applications are visible. Operating system sessions include browsers, video
 * players and calls, and the media kind they report cannot be trusted, so the default is an allow list.
 *
 * <p>A rule is a name such as {@code spotify}. It matches an application whose id equals it, or contains it as one
 * part when the id is split at dots, underscores, hyphens, slashes, spaces and exclamation marks, ignoring case. So
 * {@code spotify} matches {@code Spotify.exe}, {@code com.spotify.client} and {@code org.mpris.MediaPlayer2.spotify}.
 * A denied application is never visible. Instances are immutable; every method returns a new one.
 */
public final class AppRules {
    private static final Pattern SEPARATORS = Pattern.compile("[._!\\-/\\\\\\s]+");
    private static final Set<String> MUSIC_PLAYERS = Set.of(
            "spotify",
            "tidal",
            "deezer",
            "qobuz",
            "itunes",
            "applemusic",
            "applemusicwin",
            "com.apple.music",
            "amazonmusic",
            "foobar2000",
            "musicbee",
            "winamp",
            "aimp",
            "zunemusic",
            "dopamine",
            "plexamp",
            "cider",
            "feishin",
            "sonixd",
            "museeks",
            "harmonoid",
            "nuclear",
            "swinsian",
            "vox",
            "rhythmbox",
            "strawberry",
            "clementine",
            "elisa",
            "lollypop",
            "audacious",
            "deadbeef",
            "quodlibet",
            "amberol",
            "tauon",
            "cmus",
            "mpd",
            "ncspot",
            "spotifyd",
            "gmusicbrowser");

    private final boolean everything;
    private final Set<String> allowed;
    private final Set<String> denied;
    private final Set<String> video;

    private AppRules(boolean everything, Set<String> allowed, Set<String> denied, Set<String> video) {
        this.everything = everything;
        this.allowed = allowed;
        this.denied = denied;
        this.video = video;
    }

    /** Well-known dedicated music players only. Browsers and unknown applications stay hidden until allowed. */
    public static AppRules defaults() {
        return new AppRules(false, MUSIC_PLAYERS, Set.of(), Set.of());
    }

    /** Nothing is visible until allowed. */
    public static AppRules none() {
        return new AppRules(false, Set.of(), Set.of(), Set.of());
    }

    /**
     * Every session is visible, including browser tabs (private ones too), video players and calls. Only for
     * programs whose user asked for exactly that.
     */
    public static AppRules all() {
        return new AppRules(true, Set.of(), Set.of(), Set.of());
    }

    public AppRules allow(String... apps) {
        return new AppRules(everything, plus(allowed, apps), denied, video);
    }

    public AppRules deny(String... apps) {
        return new AppRules(everything, allowed, plus(denied, apps), video);
    }

    /** Allows applications and also shows what they report as video. */
    public AppRules allowVideo(String... apps) {
        return new AppRules(everything, plus(allowed, apps), denied, plus(video, apps));
    }

    /** Whether a session of {@code app} reporting {@code kind} may be shown. */
    public boolean visible(SourceApp app, MediaKind kind) {
        String id = app.id().toLowerCase(Locale.ROOT);
        String[] parts = SEPARATORS.split(id);
        if (matches(denied, id, parts)) {
            return false;
        }
        if (everything) {
            return true;
        }
        if (kind == MediaKind.VIDEO && !matches(video, id, parts)) {
            return false;
        }
        return matches(allowed, id, parts);
    }

    /** Whether the single rule {@code rule} matches the application id {@code appId}. */
    public static boolean matches(String rule, String appId) {
        String id = appId.toLowerCase(Locale.ROOT);
        return matches(Set.of(rule.toLowerCase(Locale.ROOT)), id, SEPARATORS.split(id));
    }

    private static boolean matches(Set<String> rules, String id, String[] parts) {
        if (rules.isEmpty()) {
            return false;
        }
        if (rules.contains(id)) {
            return true;
        }
        for (String part : parts) {
            if (rules.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> plus(Set<String> set, String[] more) {
        Set<String> out = new HashSet<>(set);
        for (String app : more) {
            out.add(app.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }
}
