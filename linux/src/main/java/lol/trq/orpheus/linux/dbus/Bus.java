/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.dbus;

import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A connection to a D-Bus message bus: authentication, sending, and one reader thread that completes method calls
 * and hands every other message to a listener. Small on purpose: only what talking to media players needs.
 */
public final class Bus implements AutoCloseable {
    private final ByteChannel channel;
    private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final Consumer<Message> listener;
    private final Runnable onClose;
    private long serial;
    private volatile boolean closed;
    private Thread reader;

    private Bus(ByteChannel channel, Consumer<Message> listener, Runnable onClose) {
        this.channel = channel;
        this.listener = listener;
        this.onClose = onClose;
    }

    /**
     * Opens a Unix socket, authenticates as the current user and starts reading.
     *
     * @param socketPath the bus socket
     * @param listener receives signals and incoming method calls, on the reader thread
     * @param onClose runs once, on the reader thread, when the connection ends for any reason but {@link #close()}
     */
    public static Bus connect(String socketPath, Consumer<Message> listener, Runnable onClose) throws IOException {
        ByteChannel channel = openUnix(socketPath);
        try {
            authenticate(channel);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        Bus bus = new Bus(channel, listener, onClose);
        bus.reader = new Thread(bus::readLoop, "orpheus-dbus");
        bus.reader.setDaemon(true);
        bus.reader.start();
        return bus;
    }

    /**
     * Unix sockets are reached through reflection: tools that rewrite a jar for older Java versions replace direct
     * calls with stubs that fail even on a runtime that has the real thing.
     */
    private static ByteChannel openUnix(String socketPath) throws IOException {
        try {
            Object address = Class.forName("java.net.UnixDomainSocketAddress")
                    .getMethod("of", String.class)
                    .invoke(null, socketPath);
            ProtocolFamily family = StandardProtocolFamily.valueOf("UNIX");
            SocketChannel socket = (SocketChannel)
                    SocketChannel.class.getMethod("open", ProtocolFamily.class).invoke(null, family);
            try {
                socket.connect((SocketAddress) address);
            } catch (IOException | RuntimeException e) {
                socket.close();
                throw e;
            }
            return socket;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("cannot open a unix socket: " + e.getCause());
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError e) {
            throw new IOException("this Java runtime has no unix socket support (needs Java 16 or newer)");
        }
    }

    private static void authenticate(ByteChannel channel) throws IOException {
        String uid = uid();
        StringBuilder hex = new StringBuilder();
        for (byte b : uid.getBytes(StandardCharsets.US_ASCII)) {
            hex.append(String.format("%02x", b));
        }
        writeAll(
                channel,
                ("\0AUTH EXTERNAL" + (uid.isEmpty() ? "" : " " + hex) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        String answer = line(channel);
        if (answer.startsWith("DATA")) {
            writeAll(channel, "DATA\r\n".getBytes(StandardCharsets.US_ASCII));
            answer = line(channel);
        }
        if (!answer.startsWith("OK")) {
            throw new IOException("the bus refused authentication: " + answer);
        }
        writeAll(channel, "BEGIN\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static String uid() {
        try {
            Object uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid");
            return String.valueOf(uid);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String line(ByteChannel channel) throws IOException {
        StringBuilder out = new StringBuilder();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (out.length() < 512) {
            one.clear();
            if (channel.read(one) < 0) {
                throw new EOFException("the bus closed the connection during authentication");
            }
            char c = (char) one.get(0);
            if (c == '\n') {
                return out.toString().trim();
            }
            out.append(c);
        }
        throw new IOException("authentication reply too long");
    }

    private static void writeAll(ByteChannel channel, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** Sends a message without waiting for anything. */
    public void send(Message message) throws IOException {
        synchronized (writeLock) {
            writeAll(channel, message.encode(++serial));
        }
    }

    /**
     * Sends a method call. The future completes with the reply, which may be an error reply, or fails when the
     * connection ends first. It never completes on its own if the peer stays silent: callers add a timeout.
     */
    public CompletableFuture<Message> call(Message message) {
        CompletableFuture<Message> reply = new CompletableFuture<>();
        try {
            synchronized (writeLock) {
                long id = ++serial;
                pending.put(id, reply);
                reply.whenComplete((value, error) -> pending.remove(id));
                writeAll(channel, message.encode(id));
            }
            if (closed) {
                reply.completeExceptionally(new IOException("connection closed"));
            }
        } catch (IOException | RuntimeException e) {
            reply.completeExceptionally(e);
        }
        return reply;
    }

    private void readLoop() {
        try {
            while (!closed) {
                byte[] header = readFully(16);
                byte[] rest = readFully(Message.remaining(header));
                byte[] all = new byte[16 + rest.length];
                System.arraycopy(header, 0, all, 0, 16);
                System.arraycopy(rest, 0, all, 16, rest.length);
                Message message;
                try {
                    message = Message.decode(all);
                } catch (WireException e) {
                    continue;
                }
                if (message.type() == Message.RETURN || message.type() == Message.ERROR) {
                    CompletableFuture<Message> reply = pending.remove(message.replySerial());
                    if (reply != null) {
                        reply.complete(message);
                    }
                } else {
                    try {
                        listener.accept(message);
                    } catch (RuntimeException e) {
                        // a listener bug must not end the connection
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // falls through to the shutdown below
        }
        boolean unexpected = !closed;
        shutdown();
        if (unexpected) {
            onClose.run();
        }
    }

    private byte[] readFully(int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException();
            }
        }
        return buffer.array();
    }

    private void shutdown() {
        closed = true;
        try {
            channel.close();
        } catch (IOException e) {
            // already gone
        }
        IOException reason = new IOException("connection closed");
        for (CompletableFuture<Message> reply : pending.values()) {
            reply.completeExceptionally(reason);
        }
    }

    /** Closes the connection, fails calls still waiting and stops the reader thread. */
    @Override
    public void close() {
        shutdown();
        Thread thread = reader;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
