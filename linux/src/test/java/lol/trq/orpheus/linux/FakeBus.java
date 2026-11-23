/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lol.trq.orpheus.linux.dbus.Message;
import lol.trq.orpheus.linux.dbus.Variant;

/**
 * A scripted stand-in for the bus daemon and the players on it, listening on a real Unix socket. It speaks just
 * enough of the protocol for the client under test and records every call a player receives.
 */
final class FakeBus implements AutoCloseable {
    static final String OWNER = ":1.50";

    final List<Message> calls = new CopyOnWriteArrayList<>();
    final Map<String, Map<String, Object>> players = new LinkedHashMap<>();
    final AtomicInteger connections = new AtomicInteger();
    volatile boolean silent;
    private final Path directory = Files.createTempDirectory("ob");
    private final Path socket = directory.resolve("bus");
    private final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    private volatile SocketChannel client;
    private volatile boolean closed;
    private long serial = 1000;

    FakeBus() throws IOException {
        server.bind(UnixDomainSocketAddress.of(socket));
        Thread thread = new Thread(this::serve, "fake-bus");
        thread.setDaemon(true);
        thread.start();
    }

    Map<String, String> environment() {
        return Map.of("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + socket + ",guid=00");
    }

    static Map<String, Object> player(String title) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mpris:trackid", new Variant("o", "/track/1"));
        metadata.put("mpris:length", new Variant("x", 180_000_000L));
        metadata.put("xesam:title", new Variant("s", title));
        metadata.put("xesam:artist", new Variant("as", List.of("Artist One", "Artist Two")));
        metadata.put("xesam:album", new Variant("s", "Album"));
        Map<String, Object> properties = new HashMap<>();
        properties.put("PlaybackStatus", new Variant("s", "Playing"));
        properties.put("Metadata", new Variant("a{sv}", metadata));
        properties.put("Position", new Variant("x", 42_000_000L));
        properties.put("Rate", new Variant("d", 1.0));
        properties.put("MinimumRate", new Variant("d", 0.5));
        properties.put("MaximumRate", new Variant("d", 2.0));
        properties.put("Shuffle", new Variant("b", false));
        properties.put("LoopStatus", new Variant("s", "None"));
        properties.put("Volume", new Variant("d", 0.8));
        for (String can : List.of("CanControl", "CanPlay", "CanPause", "CanSeek", "CanGoNext")) {
            properties.put(can, new Variant("b", true));
        }
        properties.put("CanGoPrevious", new Variant("b", false));
        return properties;
    }

    private void serve() {
        while (!closed) {
            try (SocketChannel channel = server.accept()) {
                client = channel;
                connections.incrementAndGet();
                line(channel);
                write(channel, "OK 00\r\n".getBytes(StandardCharsets.US_ASCII));
                line(channel);
                while (true) {
                    byte[] header = read(channel, 16);
                    byte[] rest = read(channel, Message.remaining(header));
                    byte[] all = Arrays.copyOf(header, 16 + rest.length);
                    System.arraycopy(rest, 0, all, 16, rest.length);
                    Message reply = answer(Message.decode(all));
                    if (reply != null) {
                        send(reply);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // the client went away, or the test dropped the connection; accept the next one
            }
        }
    }

    private Message answer(Message call) {
        Message from = new Message(call.type(), call.serial(), 0, null, null, null, null, null, ":1.1", "", List.of());
        if ("org.freedesktop.DBus".equals(call.destination())) {
            return switch (call.member()) {
                case "Hello" -> from.reply("s", ":1.1");
                case "ListNames" -> from.reply("as", List.copyOf(players.keySet()));
                case "GetNameOwner" -> from.reply("s", OWNER);
                default -> from.reply("");
            };
        }
        Map<String, Object> properties = players.get(call.destination());
        if (properties == null) {
            return from.errorReply("org.freedesktop.DBus.Error.ServiceUnknown", "no such name");
        }
        if ("GetAll".equals(call.member())) {
            return call.body().get(0).equals("org.mpris.MediaPlayer2")
                    ? from.reply("a{sv}", Map.of("Identity", new Variant("s", "Fake Player")))
                    : from.reply("a{sv}", properties);
        }
        if ("Get".equals(call.member())) {
            return from.reply("v", properties.get((String) call.body().get(1)));
        }
        calls.add(call);
        if (silent) {
            return null;
        }
        if ("Next".equals(call.member())) {
            return from.errorReply("org.example.Error.Failed", "end of the queue");
        }
        return from.reply("");
    }

    synchronized void send(Message message) throws IOException {
        write(client, message.encode(++serial));
    }

    /** Sends a signal as if it came from the unique name {@code sender}. */
    void signal(String sender, Message signal) throws IOException {
        byte[] data = signal.encode(++serial);
        Message decoded = Message.decode(data);
        Message stamped = new Message(
                Message.SIGNAL,
                0,
                0,
                decoded.path(),
                decoded.iface(),
                decoded.member(),
                null,
                null,
                null,
                decoded.signature(),
                signal.body());
        byte[] plain = stamped.encode(++serial);
        // the daemon adds the sender field; rebuild the message with it
        write(client, withSender(stamped, sender, plain));
    }

    private byte[] withSender(Message message, String sender, byte[] fallback) throws IOException {
        lol.trq.orpheus.linux.dbus.Wire.Writer content = new lol.trq.orpheus.linux.dbus.Wire.Writer()
                .body(message.signature(), message.body().toArray());
        lol.trq.orpheus.linux.dbus.Wire.Writer fields = new lol.trq.orpheus.linux.dbus.Wire.Writer();
        fields.pad(8).write("y", 1).write("v", new Variant("o", message.path()));
        fields.pad(8).write("y", 2).write("v", new Variant("s", message.iface()));
        fields.pad(8).write("y", 3).write("v", new Variant("s", message.member()));
        fields.pad(8).write("y", 7).write("v", new Variant("s", sender));
        fields.pad(8).write("y", 8).write("v", new Variant("g", message.signature()));
        lol.trq.orpheus.linux.dbus.Wire.Writer out = new lol.trq.orpheus.linux.dbus.Wire.Writer();
        out.write("y", (int) 'l').write("y", 4).write("y", 1).write("y", 1);
        out.write("u", (long) content.size()).write("u", ++serial).write("u", (long) fields.size());
        return out.raw(fields.bytes()).pad(8).raw(content.bytes()).bytes();
    }

    void changed(String property, Variant value) throws IOException {
        signal(
                OWNER,
                Message.signal(
                        "/org/mpris/MediaPlayer2",
                        "org.freedesktop.DBus.Properties",
                        "PropertiesChanged",
                        "sa{sv}as",
                        "org.mpris.MediaPlayer2.Player",
                        Map.of(property, value),
                        List.of()));
    }

    void nameOwnerChanged(String name, String oldOwner, String newOwner) throws IOException {
        signal(
                "org.freedesktop.DBus",
                Message.signal(
                        "/org/freedesktop/DBus",
                        "org.freedesktop.DBus",
                        "NameOwnerChanged",
                        "sss",
                        name,
                        oldOwner,
                        newOwner));
    }

    /** Drops the connection, as when the daemon restarts. */
    void drop() throws IOException {
        client.close();
    }

    private static void line(SocketChannel channel) throws IOException {
        ByteBuffer one = ByteBuffer.allocate(1);
        while (true) {
            one.clear();
            if (channel.read(one) < 0) {
                throw new IOException("closed");
            }
            if (one.get(0) == '\n') {
                return;
            }
        }
    }

    private static byte[] read(SocketChannel channel, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("closed");
            }
        }
        return buffer.array();
    }

    private static void write(SocketChannel channel, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        SocketChannel current = client;
        if (current != null) {
            current.close();
        }
        Files.deleteIfExists(socket);
        Files.deleteIfExists(directory);
    }
}
