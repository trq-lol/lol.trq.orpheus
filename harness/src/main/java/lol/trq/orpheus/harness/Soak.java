/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import lol.trq.orpheus.AppRules;
import lol.trq.orpheus.Orpheus;

/**
 * Runs the library against a publisher that keeps changing tracks, and samples resource use. Growth is judged between
 * the medians of the first and the last five samples after the warm-up.
 */
final class Soak {
    private Soak() {}

    private static long limit(String key) {
        return switch (key) {
            case "privateBytes" -> 4L << 20;
            case "residentBytes" -> 16L << 20;
            default -> 2;
        };
    }

    private static long median(List<Map<String, Object>> samples, String key) {
        long[] values = samples.stream()
                .filter(s -> s.get(key) instanceof Number)
                .mapToLong(s -> ((Number) s.get(key)).longValue())
                .sorted()
                .toArray();
        return values.length == 0 ? 0 : values[values.length / 2];
    }

    static int run(Map<String, String> options) throws Exception {
        double minutes = Double.parseDouble(options.getOrDefault("minutes", "60"));
        long sampleEverySeconds = Long.parseLong(options.getOrDefault("sample", "60"));
        long trackNanos = Long.parseLong(options.getOrDefault("track-millis", "5000")) * 1_000_000L;
        boolean covers = !options.containsKey("no-covers");
        boolean idle = options.containsKey("idle");
        Rig rig = Rig.detect(options.containsKey("publisher") ? Path.of(options.get("publisher")) : null);
        Publisher publisher = rig.publisher();
        Orpheus orpheus = Orpheus.builder()
                .source(rig.source())
                .apps(AppRules.none().allow(publisher.appRule()))
                .coverCacheBytes(Long.parseLong(options.getOrDefault("cache-bytes", Long.toString(32L << 20))))
                .build();
        orpheus.start();
        long reads = 0;
        try {
            publisher.track("Soak 0", "Soak Artist", "Soak Album", 200_000, 0x336699, false);
            publisher.state("playing");
            LockSupport.parkNanos(5_000_000_000L);
            long end = System.nanoTime() + (long) (minutes * 60e9);
            long nextSample = System.nanoTime();
            long nextTrack = System.nanoTime() + trackNanos;
            int track = 0;
            double warmup =
                    Double.parseDouble(options.getOrDefault("warmup", Double.toString(Math.min(10, minutes / 4))));
            List<Map<String, Object>> judged = new ArrayList<>();
            Map<String, Object> first = null;
            Map<String, Object> last = null;
            long cpuAtStart = -1;
            long libraryCpuAtStart = -1;
            long startedAt = System.nanoTime();
            while (System.nanoTime() < end) {
                long now = System.nanoTime();
                if (now >= nextSample) {
                    System.gc();
                    last = ProcessStats.sample();
                    last.put("minute", Math.round((now - startedAt) / 6e8) / 100.0);
                    last.put("tracks", track);
                    last.put("title", orpheus.playback().track().title());
                    if (first == null) {
                        first = last;
                        cpuAtStart = ((Number) last.get("cpuMillis")).longValue();
                        libraryCpuAtStart = ((Number) last.get("libraryCpuMillis")).longValue();
                    }
                    System.out.println(Json.write(last));
                    if (((Number) last.get("minute")).doubleValue() >= warmup) {
                        judged.add(last);
                    }
                    nextSample = now + sampleEverySeconds * 1_000_000_000L;
                }
                if (!idle && now >= nextTrack) {
                    track++;
                    publisher.track(
                            "Soak " + track,
                            "Soak Artist",
                            "Soak Album",
                            200_000,
                            covers ? 0x100000 + track * 977 % 0xEFFFFF : null,
                            false);
                    if (track % 5 == 0) {
                        publisher.state(track % 10 == 0 ? "paused" : "playing");
                    }
                    nextTrack = now + trackNanos;
                }
                if (idle) {
                    LockSupport.parkNanos(200_000_000L);
                } else {
                    orpheus.snapshot().playback().timeline().progressAt(now);
                    reads++;
                    LockSupport.parkNanos(16_000_000L);
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("summary", idle ? "idle" : "soak");
            summary.put("minutes", minutes);
            summary.put("frameReads", reads);
            summary.putAll(Main.environment());
            boolean grew = false;
            if (first != null && last != null) {
                for (String key :
                        new String[] {"javaThreads", "osThreads", "handles", "children", "residentBytes", "privateBytes"
                        }) {
                    if (!judged.isEmpty() && last.get(key) instanceof Number) {
                        int window = Math.min(5, (judged.size() + 1) / 2);
                        long growth = median(judged.subList(judged.size() - window, judged.size()), key)
                                - median(judged.subList(0, window), key);
                        summary.put(key + "Growth", growth);
                        grew |= growth > limit(key);
                    }
                }
                long cpu = ((Number) last.get("cpuMillis")).longValue() - cpuAtStart;
                double wall =
                        ((Number) last.get("minute")).doubleValue() - ((Number) first.get("minute")).doubleValue();
                summary.put("cpuPercentOfOneCore", wall <= 0 ? -1 : Math.round(cpu / (wall * 600.0) * 1000) / 1000.0);
                long library = ((Number) last.get("libraryCpuMillis")).longValue() - libraryCpuAtStart;
                summary.put(
                        "libraryCpuPercentOfOneCore",
                        wall <= 0 || libraryCpuAtStart < 0 ? -1 : Math.round(library / (wall * 600.0) * 1000) / 1000.0);
            }
            if (judged.size() < 2) {
                summary.put("note", "too short to judge growth");
            }
            summary.put("warmupMinutes", warmup);
            summary.put("status", grew || judged.size() < 2 ? "fail" : "pass");
            System.out.println(Json.write(summary));
            return grew ? 1 : 0;
        } finally {
            orpheus.stop();
            publisher.close();
        }
    }
}
