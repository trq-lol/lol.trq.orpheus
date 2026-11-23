/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.dbus;

import java.nio.ByteOrder;
import java.util.List;

/**
 * One D-Bus message. Unused header fields are null.
 *
 * @param type {@link #CALL}, {@link #RETURN}, {@link #ERROR} or {@link #SIGNAL}
 * @param serial the sender's serial number; assigned on sending
 * @param replySerial the serial this message answers, or 0
 * @param path the object path
 * @param iface the interface
 * @param member the method or signal name
 * @param error the error name of an error reply
 * @param destination the bus name the message goes to
 * @param sender the unique bus name it came from
 * @param signature the body signature, empty for no body
 * @param body the body values, one per complete type
 */
public record Message(
        int type,
        long serial,
        long replySerial,
        String path,
        String iface,
        String member,
        String error,
        String destination,
        String sender,
        String signature,
        List<Object> body) {
    public static final int CALL = 1;
    public static final int RETURN = 2;
    public static final int ERROR = 3;
    public static final int SIGNAL = 4;

    /** The largest message this client reads. The protocol allows more; no media player needs it. */
    public static final int MAX_BYTES = 8 << 20;

    private static final int NO_REPLY_EXPECTED = 1;

    public static Message call(
            String destination, String path, String iface, String member, String signature, Object... body) {
        return new Message(CALL, 0, 0, path, iface, member, null, destination, null, signature, List.of(body));
    }

    public static Message signal(String path, String iface, String member, String signature, Object... body) {
        return new Message(SIGNAL, 0, 0, path, iface, member, null, null, null, signature, List.of(body));
    }

    public Message reply(String replySignature, Object... values) {
        return new Message(RETURN, 0, serial, null, null, null, null, sender, null, replySignature, List.of(values));
    }

    public Message errorReply(String name, String text) {
        return new Message(ERROR, 0, serial, null, null, null, name, sender, null, "s", List.of(text));
    }

    /** The first body value as text, or empty. Error replies carry their explanation there. */
    public String text() {
        return !body.isEmpty() && body.get(0) instanceof String s ? s : "";
    }

    public byte[] encode(long withSerial) throws WireException {
        Wire.Writer content = new Wire.Writer().body(signature, body.toArray());
        Wire.Writer fields = new Wire.Writer();
        field(fields, 1, "o", path);
        field(fields, 2, "s", iface);
        field(fields, 3, "s", member);
        field(fields, 4, "s", error);
        if (replySerial != 0) {
            fields.pad(8).write("y", 5).write("v", new Variant("u", replySerial));
        }
        field(fields, 6, "s", destination);
        if (!signature.isEmpty()) {
            field(fields, 8, "g", signature);
        }
        Wire.Writer out = new Wire.Writer();
        out.write("y", (int) 'l')
                .write("y", type)
                .write("y", type == SIGNAL ? NO_REPLY_EXPECTED : 0)
                .write("y", 1);
        out.write("u", (long) content.size()).write("u", withSerial).write("u", (long) fields.size());
        out.raw(fields.bytes()).pad(8).raw(content.bytes());
        return out.bytes();
    }

    private static void field(Wire.Writer fields, int code, String type, String value) throws WireException {
        if (value != null) {
            fields.pad(8).write("y", code).write("v", new Variant(type, value));
        }
    }

    /** The number of bytes that follow the 16-byte fixed header. */
    public static int remaining(byte[] header) throws WireException {
        ByteOrder order = order(header);
        java.nio.ByteBuffer h = java.nio.ByteBuffer.wrap(header).order(order);
        long bodyLength = h.getInt(4) & 0xFFFFFFFFL;
        long fieldsLength = h.getInt(12) & 0xFFFFFFFFL;
        long total = (fieldsLength + 7) / 8 * 8 + bodyLength;
        if (header[3] != 1 || total + 16 > MAX_BYTES) {
            throw new WireException("message too large or of an unknown protocol version");
        }
        return (int) total;
    }

    private static ByteOrder order(byte[] header) throws WireException {
        if (header[0] == 'l') {
            return ByteOrder.LITTLE_ENDIAN;
        }
        if (header[0] == 'B') {
            return ByteOrder.BIG_ENDIAN;
        }
        throw new WireException("unknown byte order");
    }

    /** Decodes a complete message: the fixed header followed by everything {@link #remaining} announced. */
    @SuppressWarnings("unchecked")
    public static Message decode(byte[] data) throws WireException {
        ByteOrder order = order(data);
        Wire.Reader reader = new Wire.Reader(data, 4, order);
        long bodyLength = (Long) reader.read("u");
        long serial = (Long) reader.read("u");
        String path = null;
        String iface = null;
        String member = null;
        String error = null;
        String destination = null;
        String sender = null;
        String signature = "";
        long replySerial = 0;
        for (Object entry : (List<Object>) reader.read("a(yv)")) {
            List<Object> field = (List<Object>) entry;
            Object value = field.get(1);
            switch ((Integer) field.get(0)) {
                case 1 -> path = string(value);
                case 2 -> iface = string(value);
                case 3 -> member = string(value);
                case 4 -> error = string(value);
                case 5 -> replySerial = value instanceof Long l ? l : 0;
                case 6 -> destination = string(value);
                case 7 -> sender = string(value);
                case 8 -> signature = string(value) == null ? "" : string(value);
                default -> {}
            }
        }
        int bodyStart = (reader.position() + 7) / 8 * 8;
        if (bodyStart + bodyLength != data.length) {
            throw new WireException("header and body lengths disagree");
        }
        reader.position(bodyStart);
        List<Object> body = reader.body(signature);
        return new Message(
                data[1], serial, replySerial, path, iface, member, error, destination, sender, signature, body);
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }
}
