/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.dbus;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The D-Bus marshalling format, driven by type signatures. Java shapes: {@code y} Integer, {@code b} Boolean,
 * {@code n q i} Integer, {@code u} Long, {@code x t} Long, {@code d} Double, {@code s o g} String, {@code v} the bare
 * value when reading and a {@link Variant} when writing, arrays List, dictionaries Map, structs List.
 */
public final class Wire {
    private static final int MAX_DEPTH = 32;

    private Wire() {}

    /** The index just past the single complete type starting at {@code start}. */
    static int typeEnd(String signature, int start) throws WireException {
        int depth = 0;
        for (int i = start; i < signature.length(); i++) {
            char c = signature.charAt(i);
            if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                if (--depth == 0) {
                    return i + 1;
                }
                if (depth < 0) {
                    break;
                }
            } else if (c != 'a' && depth == 0) {
                return i + 1;
            }
        }
        throw new WireException("bad signature " + signature);
    }

    static int alignment(char type) {
        return switch (type) {
            case 'y', 'g', 'v' -> 1;
            case 'n', 'q' -> 2;
            case 'x', 't', 'd', '(', '{' -> 8;
            default -> 4;
        };
    }

    /** Splits a signature into its complete types. */
    public static List<String> types(String signature) throws WireException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < signature.length(); ) {
            int end = typeEnd(signature, i);
            out.add(signature.substring(i, end));
            i = end;
        }
        return out;
    }

    /** Writes values in the little-endian wire format. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public byte[] bytes() {
            return out.toByteArray();
        }

        public int size() {
            return out.size();
        }

        public Writer pad(int alignment) {
            while (out.size() % alignment != 0) {
                out.write(0);
            }
            return this;
        }

        public Writer raw(byte[] data) {
            out.writeBytes(data);
            return this;
        }

        /** Writes the values of a whole body: one value per complete type in {@code signature}. */
        public Writer body(String signature, Object... values) throws WireException {
            List<String> types = types(signature);
            if (types.size() != values.length) {
                throw new WireException("signature " + signature + " needs " + types.size() + " values");
            }
            for (int i = 0; i < values.length; i++) {
                write(types.get(i), values[i]);
            }
            return this;
        }

        public Writer write(String type, Object value) throws WireException {
            try {
                put(type, value, 0);
            } catch (ClassCastException | NullPointerException e) {
                throw new WireException("value does not fit type " + type);
            }
            return this;
        }

        private void put(String type, Object value, int depth) throws WireException {
            if (depth > MAX_DEPTH) {
                throw new WireException("nesting too deep");
            }
            char c = type.charAt(0);
            pad(alignment(c));
            switch (c) {
                case 'y' -> out.write(((Number) value).intValue());
                case 'b' -> fixed(((Boolean) value) ? 1 : 0, 4);
                case 'n', 'q' -> fixed(((Number) value).longValue(), 2);
                case 'i', 'u', 'h' -> fixed(((Number) value).longValue(), 4);
                case 'x', 't' -> fixed(((Number) value).longValue(), 8);
                case 'd' -> fixed(Double.doubleToRawLongBits(((Number) value).doubleValue()), 8);
                case 's', 'o' -> {
                    byte[] text = ((String) value).getBytes(StandardCharsets.UTF_8);
                    fixed(text.length, 4);
                    out.writeBytes(text);
                    out.write(0);
                }
                case 'g' -> signature((String) value);
                case 'v' -> {
                    Variant variant = (Variant) value;
                    signature(variant.signature());
                    put(variant.signature(), variant.value(), depth + 1);
                }
                case '(' -> {
                    List<?> fields = (List<?>) value;
                    List<String> types = types(type.substring(1, type.length() - 1));
                    for (int i = 0; i < types.size(); i++) {
                        put(types.get(i), fields.get(i), depth + 1);
                    }
                }
                case 'a' -> {
                    String element = type.substring(1);
                    Writer inner = new Writer();
                    int base = out.size() + 4;
                    int lead = (alignment(element.charAt(0)) - base % alignment(element.charAt(0)))
                            % alignment(element.charAt(0));
                    for (int i = 0; i < base + lead; i++) {
                        inner.out.write(0);
                    }
                    if (element.charAt(0) == '{') {
                        int keyEnd = typeEnd(element, 1);
                        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                            inner.pad(8);
                            inner.put(element.substring(1, keyEnd), entry.getKey(), depth + 1);
                            inner.put(element.substring(keyEnd, element.length() - 1), entry.getValue(), depth + 1);
                        }
                    } else {
                        for (Object item : (List<?>) value) {
                            inner.put(element, item, depth + 1);
                        }
                    }
                    byte[] data = inner.out.toByteArray();
                    fixed(data.length - base - lead, 4);
                    out.write(data, base, data.length - base);
                }
                default -> throw new WireException("unsupported type " + c);
            }
        }

        private void signature(String signature) {
            byte[] text = signature.getBytes(StandardCharsets.US_ASCII);
            out.write(text.length);
            out.writeBytes(text);
            out.write(0);
        }

        private void fixed(long value, int bytes) {
            for (int i = 0; i < bytes; i++) {
                out.write((int) (value >>> (8 * i)) & 0xFF);
            }
        }
    }

    /** Reads values from untrusted bytes. Every length is checked against what is left. */
    public static final class Reader {
        private final ByteBuffer in;

        public Reader(byte[] data, int offset, ByteOrder order) {
            this.in = ByteBuffer.wrap(data).order(order);
            in.position(offset);
        }

        public int position() {
            return in.position();
        }

        public void position(int position) throws WireException {
            if (position < 0 || position > in.limit()) {
                throw new WireException("position outside the message");
            }
            in.position(position);
        }

        public List<Object> body(String signature) throws WireException {
            List<Object> values = new ArrayList<>();
            for (String type : types(signature)) {
                values.add(read(type));
            }
            return values;
        }

        public Object read(String type) throws WireException {
            try {
                return get(type, 0);
            } catch (RuntimeException e) {
                throw new WireException("truncated or malformed value of type " + type);
            }
        }

        private void align(int alignment) {
            in.position((in.position() + alignment - 1) / alignment * alignment);
        }

        private Object get(String type, int depth) throws WireException {
            if (depth > MAX_DEPTH) {
                throw new WireException("nesting too deep");
            }
            char c = type.charAt(0);
            align(alignment(c));
            switch (c) {
                case 'y':
                    return in.get() & 0xFF;
                case 'b':
                    return in.getInt() != 0;
                case 'n':
                    return (int) in.getShort();
                case 'q':
                    return in.getShort() & 0xFFFF;
                case 'i', 'h':
                    return in.getInt();
                case 'u':
                    return in.getInt() & 0xFFFFFFFFL;
                case 'x', 't':
                    return in.getLong();
                case 'd':
                    return in.getDouble();
                case 's', 'o': {
                    int length = in.getInt();
                    if (length < 0 || length > in.remaining() - 1) {
                        throw new WireException("string longer than the message");
                    }
                    String text = new String(in.array(), in.position(), length, StandardCharsets.UTF_8);
                    in.position(in.position() + length + 1);
                    return text;
                }
                case 'g':
                    return signature();
                case 'v': {
                    String inner = signature();
                    if (typeEnd(inner, 0) != inner.length()) {
                        throw new WireException("a variant holds exactly one type");
                    }
                    return get(inner, depth + 1);
                }
                case '(': {
                    List<Object> fields = new ArrayList<>();
                    for (String field : types(type.substring(1, type.length() - 1))) {
                        fields.add(get(field, depth + 1));
                    }
                    return fields;
                }
                case 'a': {
                    int length = in.getInt();
                    String element = type.substring(1);
                    align(alignment(element.charAt(0)));
                    if (length < 0 || length > in.remaining()) {
                        throw new WireException("array longer than the message");
                    }
                    int end = in.position() + length;
                    if (element.charAt(0) == '{') {
                        int keyEnd = typeEnd(element, 1);
                        String key = element.substring(1, keyEnd);
                        String value = element.substring(keyEnd, element.length() - 1);
                        Map<Object, Object> map = new LinkedHashMap<>();
                        while (in.position() < end) {
                            align(8);
                            Object k = get(key, depth + 1);
                            map.put(k, get(value, depth + 1));
                        }
                        return map;
                    }
                    List<Object> list = new ArrayList<>();
                    while (in.position() < end) {
                        int before = in.position();
                        list.add(get(element, depth + 1));
                        if (in.position() == before) {
                            throw new WireException("array element of no size");
                        }
                    }
                    return list;
                }
                default:
                    throw new WireException("unsupported type " + c);
            }
        }

        private String signature() {
            int length = in.get() & 0xFF;
            String text = new String(in.array(), in.position(), length, StandardCharsets.US_ASCII);
            in.position(in.position() + length + 1);
            return text;
        }
    }
}
