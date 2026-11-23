/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

/**
 * One session known to the library, for pickers and "allow this app" prompts. Hidden sessions carry no track data.
 *
 * @param sourceId the source
 * @param sessionId the session inside the source
 * @param app the application
 * @param state the playback state
 * @param visible whether the app rules let this session through
 */
public record SessionInfo(String sourceId, String sessionId, SourceApp app, PlaybackState state, boolean visible) {}
