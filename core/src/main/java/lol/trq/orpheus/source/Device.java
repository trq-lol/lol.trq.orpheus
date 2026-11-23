/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

/**
 * An output a source can play on.
 *
 * @param id identifies the device for transfer
 * @param name a display name
 * @param type a free-form kind such as "computer" or "speaker"; may be empty
 * @param active whether playback is on this device
 */
public record Device(String id, String name, String type, boolean active) {}
