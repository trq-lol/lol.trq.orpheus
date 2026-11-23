/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.dbus;

/**
 * A value together with its D-Bus type, needed when writing a variant. Reading unwraps variants to the bare value.
 *
 * @param signature the type of {@code value}
 * @param value the value
 */
public record Variant(String signature, Object value) {}
