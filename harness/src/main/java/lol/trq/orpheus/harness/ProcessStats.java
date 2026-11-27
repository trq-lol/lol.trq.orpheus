/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Resource numbers of this process as the operating system sees them: threads, handles, memory, CPU time. */
final class ProcessStats {
    /** Threads of the library, its native code and the D-Bus runtime the native code uses. */
    private static final Pattern LIBRARY_THREAD = Pattern.compile("orpheus|zbus|async-io|blocking");

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
        out.put("libraryCpuMillis", libraryCpuMillis());
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

    /**
     * CPU time of the library's own threads, so the JVM's background work (compiler, collector) is not counted.
     * On Linux every thread is read from the system, which also counts the native library's threads; elsewhere only
     * Java threads are seen.
     */
    private static long libraryCpuMillis() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux")) {
            try (Stream<Path> tasks = Files.list(Path.of("/proc/self/task"))) {
                long ticks = 0;
                for (Path task : (Iterable<Path>) tasks::iterator) {
                    try {
                        String name = Files.readString(task.resolve("comm")).trim();
                        if (!LIBRARY_THREAD.matcher(name).lookingAt()) {
                            continue;
                        }
                        String stat = Files.readString(task.resolve("stat"));
                        String[] fields =
                                stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                        ticks += Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
                    } catch (IOException | RuntimeException e) {
                        // the thread ended while it was read
                    }
                }
                return ticks * 10;
            } catch (IOException e) {
                return -1;
            }
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long nanos = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("orpheus-")) {
                nanos += Math.max(0, threads.getThreadCpuTime(thread.getId()));
            }
        }
        return nanos / 1_000_000;
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
