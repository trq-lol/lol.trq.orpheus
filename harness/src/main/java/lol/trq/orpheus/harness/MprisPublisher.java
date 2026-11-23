/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lol.trq.orpheus.linux.dbus.Bus;
import lol.trq.orpheus.linux.dbus.BusAddress;
import lol.trq.orpheus.linux.dbus.Message;
import lol.trq.orpheus.linux.dbus.Variant;

/** A stand-in MPRIS player on the real session bus. */
final class MprisPublisher implements Publisher {
    private static final String NAME = "org.mpris.MediaPlayer2.orpheusharness";
    private static final String OBJECT = "/org/mpris/MediaPlayer2";
    private static final String PLAYER = "org.mpris.MediaPlayer2.Player";
    private static final String PROPERTIES = "org.freedesktop.DBus.Properties";

    private final Bus bus;
    private final Map<String, Object> properties = new HashMap<>();
    private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final List<String> all = new CopyOnWriteArrayList<>();
    private long positionMicros;
    private long positionNanos = System.nanoTime();
    private int tracks;

    MprisPublisher() throws Exception {
        bus = Bus.connect(BusAddress.sessionSocket(System.getenv()), this::handle, () -> {});
        expect(bus.call(
                Message.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello", "")));
        expect(bus.call(Message.call(
                "org.freedesktop.DBus",
                "/org/freedesktop/DBus",
                "org.freedesktop.DBus",
                "RequestName",
                "su",
                NAME,
                4L)));
        synchronized (this) {
            properties.put("PlaybackStatus", new Variant("s", "Stopped"));
            properties.put("Metadata", new Variant("a{sv}", Map.of()));
            properties.put("Rate", new Variant("d", 1.0));
            properties.put("MinimumRate", new Variant("d", 0.25));
            properties.put("MaximumRate", new Variant("d", 4.0));
            properties.put("Shuffle", new Variant("b", false));
            properties.put("LoopStatus", new Variant("s", "None"));
            properties.put("Volume", new Variant("d", 1.0));
            for (String can : List.of("CanControl", "CanPlay", "CanPause", "CanSeek", "CanGoNext", "CanGoPrevious")) {
                properties.put(can, new Variant("b", true));
            }
        }
    }

    private static void expect(java.util.concurrent.CompletableFuture<Message> reply) throws Exception {
        Message message = reply.get(10, TimeUnit.SECONDS);
        if (message.type() != Message.RETURN) {
            throw new IOException("the bus answered " + message.error() + " " + message.text());
        }
    }

    private synchronized long currentMicros() {
        Object status = ((Variant) properties.get("PlaybackStatus")).value();
        if (!"Playing".equals(status)) {
            return positionMicros;
        }
        double rate = (Double) ((Variant) properties.get("Rate")).value();
        return positionMicros + (long) ((System.nanoTime() - positionNanos) / 1000 * rate);
    }

    private void record(String command) {
        all.add(command);
        received.add(command);
    }

    private void handle(Message call) {
        if (call.type() != Message.CALL || call.member() == null) {
            return;
        }
        try {
            bus.send(answer(call));
        } catch (IOException | RuntimeException e) {
            // the connection is gone; nothing to answer
        }
    }

    private Message answer(Message call) throws IOException {
        List<Object> body = call.body();
        if (PROPERTIES.equals(call.iface())) {
            switch (call.member()) {
                case "GetAll":
                    if (PLAYER.equals(body.get(0))) {
                        Map<String, Object> copy;
                        synchronized (this) {
                            copy = new HashMap<>(properties);
                        }
                        copy.put("Position", new Variant("x", currentMicros()));
                        return call.reply("a{sv}", copy);
                    }
                    return call.reply(
                            "a{sv}",
                            Map.of(
                                    "Identity",
                                    new Variant("s", "Harness Player"),
                                    "DesktopEntry",
                                    new Variant("s", "orpheusharness")));
                case "Get":
                    if ("Position".equals(body.get(1))) {
                        return call.reply("v", new Variant("x", currentMicros()));
                    }
                    Object value;
                    synchronized (this) {
                        value = properties.get((String) body.get(1));
                    }
                    return value == null
                            ? call.errorReply("org.freedesktop.DBus.Error.UnknownProperty", "no such property")
                            : call.reply("v", value);
                case "Set": {
                    String property = (String) body.get(1);
                    Object target = body.get(2);
                    switch (property) {
                        case "Shuffle" -> {
                            record("shuffle " + target);
                            change("Shuffle", new Variant("b", target));
                        }
                        case "LoopStatus" -> {
                            record("repeat " + ("Playlist".equals(target) ? 2 : "Track".equals(target) ? 1 : 0));
                            change("LoopStatus", new Variant("s", target));
                        }
                        case "Rate" -> {
                            record("rate " + target);
                            rebase();
                            change("Rate", new Variant("d", target));
                        }
                        case "Volume" -> {
                            record("volume " + target);
                            change("Volume", new Variant("d", target));
                        }
                        default -> {
                            return call.errorReply("org.freedesktop.DBus.Error.PropertyReadOnly", property);
                        }
                    }
                    return call.reply("");
                }
                default:
                    return call.errorReply("org.freedesktop.DBus.Error.UnknownMethod", call.member());
            }
        }
        if (!PLAYER.equals(call.iface())) {
            return call.errorReply("org.freedesktop.DBus.Error.UnknownMethod", String.valueOf(call.member()));
        }
        switch (call.member()) {
            case "Play" -> {
                record("play");
                status("Playing");
            }
            case "Pause" -> {
                record("pause");
                status("Paused");
            }
            case "PlayPause" -> {
                record("toggle");
                status("Playing".equals(((Variant) properties.get("PlaybackStatus")).value()) ? "Paused" : "Playing");
            }
            case "Stop" -> {
                record("stop");
                status("Stopped");
            }
            case "Next" -> record("next");
            case "Previous" -> record("previous");
            case "Seek" -> {
                long target = currentMicros() + (Long) body.get(0);
                record("seek " + target / 1000);
                jump(target);
            }
            case "SetPosition" -> {
                record("seek " + (Long) body.get(1) / 1000);
                jump((Long) body.get(1));
            }
            default -> {
                return call.errorReply("org.freedesktop.DBus.Error.UnknownMethod", call.member());
            }
        }
        return call.reply("");
    }

    private synchronized void rebase() {
        positionMicros = currentMicros();
        positionNanos = System.nanoTime();
    }

    private void jump(long micros) throws IOException {
        synchronized (this) {
            positionMicros = micros;
            positionNanos = System.nanoTime();
        }
        bus.send(Message.signal(OBJECT, PLAYER, "Seeked", "x", micros));
    }

    private void status(String status) throws IOException {
        rebase();
        change("PlaybackStatus", new Variant("s", status));
    }

    private void change(String property, Variant value) throws IOException {
        synchronized (this) {
            properties.put(property, value);
        }
        bus.send(Message.signal(
                OBJECT, PROPERTIES, "PropertiesChanged", "sa{sv}as", PLAYER, Map.of(property, value), List.of()));
    }

    @Override
    public String appRule() {
        return "orpheusharness";
    }

    @Override
    public void track(String title, String artist, String album, long durationMillis, Integer coverRgb, boolean video)
            throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mpris:trackid", new Variant("o", "/orpheus/harness/track/" + ++tracks));
        if (durationMillis > 0) {
            metadata.put("mpris:length", new Variant("x", durationMillis * 1000));
        }
        metadata.put("xesam:title", new Variant("s", title));
        if (!artist.isEmpty()) {
            metadata.put("xesam:artist", new Variant("as", List.of(artist)));
        }
        if (!album.isEmpty()) {
            metadata.put("xesam:album", new Variant("s", album));
        }
        if (coverRgb != null) {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    image.setRGB(x, y, coverRgb);
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            metadata.put(
                    "mpris:artUrl",
                    new Variant(
                            "s", "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray())));
        }
        synchronized (this) {
            positionMicros = 0;
            positionNanos = System.nanoTime();
        }
        change("Metadata", new Variant("a{sv}", metadata));
    }

    @Override
    public void state(String state) throws Exception {
        status(
                switch (state) {
                    case "playing" -> "Playing";
                    case "paused" -> "Paused";
                    default -> "Stopped";
                });
    }

    @Override
    public void position(long millis) throws Exception {
        jump(millis * 1000);
    }

    @Override
    public void controls(boolean allControls) throws Exception {
        change("CanGoNext", new Variant("b", allControls));
        change("CanGoPrevious", new Variant("b", allControls));
    }

    @Override
    public String awaitCommand(long timeoutMillis) throws InterruptedException {
        return received.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public List<String> commands() {
        return all;
    }

    @Override
    public List<String> supportedCommands() {
        return List.of(
                "pause", "play", "toggle", "next", "previous", "seek", "shuffle", "repeat", "rate", "volume", "stop");
    }

    @Override
    public void kill() {
        bus.close();
    }

    @Override
    public void close() {
        bus.close();
    }
}
