/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Drives a native test publisher over its stdin and stdout line protocol (see the publisher sources). */
final class ProcessPublisher implements Publisher {
    private final Process process;
    private final BufferedWriter stdin;
    private final LinkedBlockingQueue<String> acks = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final List<String> all = new CopyOnWriteArrayList<>();
    private final String rule;
    private final List<String> supported;

    ProcessPublisher(Path executable, String rule, List<String> supported) throws IOException, InterruptedException {
        this.rule = rule;
        this.supported = supported;
        process = new ProcessBuilder(executable.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(this::read, "harness-publisher-output");
        reader.setDaemon(true);
        reader.start();
        if (!"ready".equals(acks.poll(15, TimeUnit.SECONDS))) {
            process.destroyForcibly();
            throw new IOException("the publisher did not start: " + executable);
        }
    }

    private void read() {
        try (BufferedReader out =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = out.readLine(); line != null; line = out.readLine()) {
                if (line.startsWith("command ")) {
                    String command = line.substring(8);
                    all.add(command);
                    received.add(command);
                } else {
                    acks.add(line);
                }
            }
        } catch (IOException e) {
            // the process ended
        }
    }

    private void send(String verb, String... fields) throws IOException, InterruptedException {
        stdin.write(verb);
        for (String field : fields) {
            stdin.write('\t');
            stdin.write(field.replace('\t', ' ').replace('\n', ' '));
        }
        stdin.write('\n');
        stdin.flush();
        String ack = acks.poll(15, TimeUnit.SECONDS);
        if (!("done " + verb).equals(ack)) {
            throw new IOException("the publisher answered '" + ack + "' to " + verb);
        }
    }

    @Override
    public String appRule() {
        return rule;
    }

    @Override
    public void track(String title, String artist, String album, long durationMillis, Integer coverRgb, boolean video)
            throws Exception {
        send(
                "track",
                title,
                artist,
                album,
                "",
                Long.toString(durationMillis),
                coverRgb == null ? "-" : String.format("%06X", coverRgb),
                video ? "video" : "music");
    }

    @Override
    public void state(String state) throws Exception {
        send("state", state);
    }

    @Override
    public void position(long millis) throws Exception {
        send("position", Long.toString(millis));
    }

    @Override
    public void controls(boolean allControls) throws Exception {
        send("controls", allControls ? "on" : "off");
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
        return supported;
    }

    @Override
    public void kill() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        try {
            stdin.write("quit\n");
            stdin.flush();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException | InterruptedException e) {
            process.destroyForcibly();
        }
    }
}
