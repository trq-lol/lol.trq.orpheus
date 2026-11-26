/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppNamesTest {
    @Test
    void executablesLoseTheirExtension() {
        assertEquals("Spotify", AppNames.windows("Spotify.exe"));
        assertEquals("Chrome", AppNames.windows("chrome.exe"));
        assertEquals("Foobar2000", AppNames.windows("foobar2000.EXE"));
    }

    @Test
    void packagedAppsUseTheirAppName() {
        assertEquals("Spotify Music", AppNames.windows("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"));
        assertEquals("Zune Music", AppNames.windows("Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic"));
    }

    @Test
    void dottedIdsUseTheLastPart() {
        assertEquals("Player", AppNames.windows("Microsoft.Media.Player"));
        assertEquals("308046B0AF4A39CB", AppNames.windows("308046B0AF4A39CB"));
    }

    @Test
    void humanizeSplitsCamelCase() {
        assertEquals("Spotify Music", AppNames.humanize("SpotifyMusic"));
        assertEquals("Vlc", AppNames.humanize("vlc"));
        assertEquals("", AppNames.humanize(""));
        assertEquals("", AppNames.windows(null));
    }
}
