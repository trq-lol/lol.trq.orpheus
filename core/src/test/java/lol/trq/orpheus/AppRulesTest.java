/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppRulesTest {
    private static boolean visible(AppRules rules, String app, MediaKind kind) {
        return rules.visible(new SourceApp(app, app), kind);
    }

    @Test
    void oneRuleMatchesTheIdsOfEveryPlatform() {
        AppRules rules = AppRules.none().allow("spotify");
        assertTrue(visible(rules, "Spotify.exe", MediaKind.MUSIC));
        assertTrue(visible(rules, "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify", MediaKind.MUSIC));
        assertTrue(visible(rules, "org.mpris.MediaPlayer2.spotify", MediaKind.UNKNOWN));
        assertTrue(visible(rules, "com.spotify.client", MediaKind.MUSIC));
        assertFalse(visible(rules, "notspotify.exe", MediaKind.MUSIC));
        assertFalse(visible(rules, "", MediaKind.MUSIC));
        AppRules dotted = AppRules.none().allow("com.apple.Music", "my-player");
        assertTrue(visible(dotted, "com.apple.music", MediaKind.MUSIC));
        assertFalse(visible(dotted, "com.apple.Safari", MediaKind.MUSIC), "one shared part is not a match");
        assertFalse(visible(dotted, "music.apple.com", MediaKind.MUSIC), "the parts must be in order");
        assertTrue(visible(dotted, "My-Player.exe", MediaKind.MUSIC));
        assertTrue(visible(AppRules.defaults(), "com.apple.Music", MediaKind.MUSIC));
    }

    @Test
    void browsersAndUnknownAppsAreHiddenByDefault() {
        AppRules rules = AppRules.defaults();
        assertFalse(visible(rules, "chrome.exe", MediaKind.MUSIC));
        assertFalse(visible(rules, "org.mpris.MediaPlayer2.firefox.instance_1_23", MediaKind.UNKNOWN));
        assertFalse(visible(rules, "some-unknown-app", MediaKind.MUSIC));
        assertTrue(visible(rules, "foobar2000.exe", MediaKind.MUSIC));
    }

    @Test
    void denyBeatsAllowAndAll() {
        assertFalse(visible(AppRules.defaults().deny("spotify"), "Spotify.exe", MediaKind.MUSIC));
        assertFalse(visible(AppRules.all().deny("chrome"), "chrome.exe", MediaKind.MUSIC));
        assertTrue(visible(AppRules.all(), "chrome.exe", MediaKind.VIDEO));
    }

    @Test
    void videoNeedsItsOwnPermission() {
        assertFalse(visible(AppRules.none().allow("vlc"), "vlc.exe", MediaKind.VIDEO));
        assertTrue(visible(AppRules.none().allow("vlc"), "vlc.exe", MediaKind.MUSIC));
        assertTrue(visible(AppRules.none().allowVideo("vlc"), "vlc.exe", MediaKind.VIDEO));
    }
}
