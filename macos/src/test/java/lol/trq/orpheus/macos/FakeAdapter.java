/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.macos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * Stands in for the helper process in tests. Arguments: a script file, a log file, then the helper arguments.
 *
 * <p>For {@code stream} it prints the script line by line. Lines starting with {@code !} are directives:
 * {@code !err text} writes to stderr, {@code !exit n} exits, {@code !hold} waits until it is killed. Any other
 * function is appended to the log; a script containing {@code !fail-commands} makes it fail instead.
 */
public final class FakeAdapter {
    private FakeAdapter() {}

    public static void main(String[] args) throws Exception {
        Path script = Path.of(args[0]);
        Path log = Path.of(args[1]);
        List<String> helper = Arrays.asList(args).subList(2, args.length);
        List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
        if (!helper.get(0).equals("stream")) {
            if (lines.contains("!fail-commands")) {
                System.err.println("Invalid command: " + String.join(" ", helper));
                System.exit(3);
            }
            Files.writeString(
                    log,
                    String.join(" ", helper) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            return;
        }
        Files.writeString(
                log, "stream started\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        for (String line : lines) {
            if (line.startsWith("!err ")) {
                System.err.println(line.substring(5));
            } else if (line.startsWith("!exit ")) {
                System.out.flush();
                System.exit(Integer.parseInt(line.substring(6)));
            } else if (line.equals("!hold")) {
                System.out.flush();
                Thread.sleep(Long.MAX_VALUE);
            } else if (!line.startsWith("!")) {
                System.out.println(line);
                System.out.flush();
            }
        }
    }
}
