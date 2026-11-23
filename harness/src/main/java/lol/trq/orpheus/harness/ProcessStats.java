/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Resource numbers of this process as the operating system sees them: threads, handles, memory, CPU time. */
final class ProcessStats {
    private ProcessStats() {}

    static Map<String, Object> sample() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("javaThreads", Thread.getAllStackTraces().size());
        out.put(
                "libraryThreads",
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().startsWith("orpheus-"))
                        .count());
        out.put(
                "cpuMillis",
                ProcessHandle.current()
                        .info()
                        .totalCpuDuration()
                        .map(d -> d.toMillis())
                        .orElse(-1L));
        out.put(
                "heapUsedBytes",
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.startsWith("windows")) {
                windows(out);
            } else if (os.startsWith("linux")) {
                linux(out);
            } else {
                unix(out);
            }
        } catch (IOException | RuntimeException e) {
            out.put("osError", e.toString());
        }
        return out;
    }

    private static void windows(Map<String, Object> out) throws IOException {
        long pid = ProcessHandle.current().pid();
        String script = "(Get-Process -Id " + pid
                + " | ForEach-Object { $_.Threads.Count, $_.HandleCount, $_.WorkingSet64, $_.PrivateMemorySize64 }) -join ' '";
        String[] parts = run("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .trim()
                .split("\\s+");
        out.put("osThreads", Long.parseLong(parts[0]));
        out.put("handles", Long.parseLong(parts[1]));
        out.put("residentBytes", Long.parseLong(parts[2]));
        out.put("privateBytes", Long.parseLong(parts[3]));
    }

    private static void linux(Map<String, Object> out) throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("Threads:")) {
                out.put("osThreads", Long.parseLong(line.substring(8).trim()));
            } else if (line.startsWith("VmRSS:")) {
                out.put("residentBytes", Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024);
            }
        }
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            out.put("handles", fds.count());
        }
        out.put("children", ProcessHandle.current().descendants().count());
    }

    private static void unix(Map<String, Object> out) throws IOException {
        long pid = ProcessHandle.current().pid();
        String[] parts =
                run("ps", "-o", "rss=", "-p", Long.toString(pid)).trim().split("\\s+");
        out.put("residentBytes", Long.parseLong(parts[0]) * 1024);
        out.put("handles", run("lsof", "-p", Long.toString(pid)).lines().count());
        out.put("children", ProcessHandle.current().descendants().count());
    }

    private static String run(String... command) throws IOException {
        Process process =
                new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
        try {
            byte[] output = process.getInputStream().readAllBytes();
            process.waitFor(30, TimeUnit.SECONDS);
            return new String(output, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } finally {
            process.destroyForcibly();
        }
    }
}
