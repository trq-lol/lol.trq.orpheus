/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.windows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** The cost of crossing into the native library and of reading what it returns. Windows only. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class NativeBench {
    private byte[] blob;

    private static void text(ByteBuffer out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.putInt(bytes.length).put(bytes);
    }

    @Setup
    public void setup() {
        String failure = Bridge.load();
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
        ByteBuffer out = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) Blob.KIND_AGE).putLong(1_000_000);
        out.put((byte) Blob.KIND_SESSIONS).putInt(2);
        for (int i = 0; i < 2; i++) {
            out.putInt(i + 1);
            text(out, "player.exe");
            text(out, "A Song");
            text(out, "An Artist");
            text(out, "An Album");
            text(out, "");
            out.putInt(3)
                    .put((byte) 1)
                    .put((byte) 1)
                    .putLong(60_000)
                    .putLong(240_000)
                    .putLong(1500);
            out.putDouble(1).put((byte) 0).put((byte) 0).putInt(0x7FF).putLong(0);
        }
        blob = new byte[out.position()];
        out.flip().get(blob);
    }

    @Benchmark
    public byte[] nativeCall() {
        return Bridge.invoke(Bridge.OP_ABI, 0, null);
    }

    @Benchmark
    public void readSessionsBlob(Blackhole sink) {
        Blob.read(blob, new Blob.Handler() {
            @Override
            public void sessions(long builtNanosAgo, List<Blob.Session> sessions) {
                sink.consume(sessions);
            }

            @Override
            public void thumbnail(long key, byte[] bytes) {}

            @Override
            public void result(long request, int status, String reason) {}

            @Override
            public void status(boolean ready, String reason) {}
        });
    }
}
