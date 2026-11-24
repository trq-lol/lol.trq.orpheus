/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.internal.MiniJson;
import org.junit.jupiter.api.Test;

class SupportedCommandsTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(String json) {
        return (Map<String, Object>) MiniJson.parse(json);
    }

    @Test
    void controlsFollowTheEnabledCommands() {
        assertEquals(
                Set.of(Control.PLAY, Control.PAUSE, Control.SEEK, Control.SHUFFLE),
                MacNowPlaying.controlsOf(state("{\"supportedCommands\":["
                        + "{\"id\":0,\"enabled\":true},{\"id\":1,\"enabled\":true},{\"id\":4,\"enabled\":false},"
                        + "{\"id\":5,\"enabled\":false},{\"id\":24,\"enabled\":true},{\"id\":26,\"enabled\":true},"
                        + "{\"id\":99,\"enabled\":true},{\"id\":3},{\"enabled\":true},null,7]}")));
    }

    @Test
    void anEmptyListOffersNothing() {
        assertEquals(Set.of(), MacNowPlaying.controlsOf(state("{\"supportedCommands\":[]}")));
    }

    @Test
    void withoutAListEveryControlIsAssumed() {
        assertEquals(9, MacNowPlaying.controlsOf(state("{}")).size());
        Set<Control> noSkip = MacNowPlaying.controlsOf(state("{\"prohibitsSkip\":true}"));
        assertEquals(8, noSkip.size());
        assertFalse(noSkip.contains(Control.NEXT));
    }

    @Test
    void modesComeFromCommandOptionsWhenTheMetadataLacksThem() {
        Map<String, Object> state = state("{\"supportedCommands\":["
                + "{\"id\":25,\"enabled\":true,\"options\":{\"kMRMediaRemoteCommandInfoRepeatMode\":3}},"
                + "{\"id\":26,\"enabled\":true,\"options\":{\"kMRMediaRemoteCommandInfoShuffleMode\":1}}]}");
        assertEquals(3, MacNowPlaying.modeOf(state, "repeatMode", 25, "kMRMediaRemoteCommandInfoRepeatMode"));
        assertEquals(1, MacNowPlaying.modeOf(state, "shuffleMode", 26, "kMRMediaRemoteCommandInfoShuffleMode"));
        assertEquals(
                2,
                MacNowPlaying.modeOf(
                        state("{\"repeatMode\":2}"), "repeatMode", 25, "kMRMediaRemoteCommandInfoRepeatMode"));
        assertEquals(0, MacNowPlaying.modeOf(state("{}"), "repeatMode", 25, "x"));
    }

    @Test
    void validationErrorsAreRecognised() {
        assertTrue(MacNowPlaying.rejected(
                "The specified command is disabled with the current now playing application: 4 (NextTrack)"));
        assertTrue(MacNowPlaying.rejected(
                "The specified command is not supported by the current now playing application: 7"));
        assertFalse(MacNowPlaying.rejected("Warning: Validation of the specified command timed out"));
    }
}
