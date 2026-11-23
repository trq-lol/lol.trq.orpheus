/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.bench;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lol.trq.orpheus.linux.dbus.Message;
import lol.trq.orpheus.linux.dbus.Variant;
import lol.trq.orpheus.linux.dbus.WireException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Decoding and encoding the D-Bus messages a media player sends most. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class WireBench {
    private byte[] propertiesChanged;
    private Message call;

    @Setup
    public void setup() throws WireException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mpris:trackid", new Variant("o", "/org/example/track/12"));
        metadata.put("mpris:length", new Variant("x", 240_000_000L));
        metadata.put("mpris:artUrl", new Variant("s", "file:///tmp/cover-12.png"));
        metadata.put("xesam:title", new Variant("s", "A Song"));
        metadata.put("xesam:artist", new Variant("as", List.of("An Artist", "Someone")));
        metadata.put("xesam:album", new Variant("s", "An Album"));
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("Metadata", new Variant("a{sv}", metadata));
        changed.put("PlaybackStatus", new Variant("s", "Playing"));
        propertiesChanged = Message.signal(
                        "/org/mpris/MediaPlayer2",
                        "org.freedesktop.DBus.Properties",
                        "PropertiesChanged",
                        "sa{sv}as",
                        "org.mpris.MediaPlayer2.Player",
                        changed,
                        List.of())
                .encode(7);
        call = Message.call(
                "org.mpris.MediaPlayer2.example",
                "/org/mpris/MediaPlayer2",
                "org.mpris.MediaPlayer2.Player",
                "SetPosition",
                "ox",
                "/org/example/track/12",
                42_000_000L);
    }

    @Benchmark
    public Message decodePropertiesChanged() throws WireException {
        return Message.decode(propertiesChanged);
    }

    @Benchmark
    public byte[] encodeCall() throws WireException {
        return call.encode(9);
    }
}
