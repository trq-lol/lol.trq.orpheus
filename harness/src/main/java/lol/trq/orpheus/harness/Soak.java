/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import lol.trq.orpheus.AppRules;
import lol.trq.orpheus.Orpheus;

/**
 * Runs the library against a publisher that keeps changing tracks, and samples resource use. The first sample is
 * taken after a warm-up; growth is judged between the first and the last sample.
 */
final class Soak {
    private Soak() {}

    static int run(Map<String, String> options) throws Exception {
        double minutes = Double.parseDouble(options.getOrDefault("minutes", "60"));
        long sampleEverySeconds = Long.parseLong(options.getOrDefault("sample", "60"));
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
            long nextTrack = System.nanoTime() + 5_000_000_000L;
            int track = 0;
            Map<String, Object> first = null;
            Map<String, Object> last = null;
            long cpuAtStart = -1;
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
                    }
                    System.out.println(Json.write(last));
                    nextSample = now + sampleEverySeconds * 1_000_000_000L;
                }
                if (!idle && now >= nextTrack) {
                    track++;
                    publisher.track(
                            "Soak " + track,
                            "Soak Artist",
                            "Soak Album",
                            200_000,
                            0x100000 + track * 977 % 0xEFFFFF,
                            false);
                    if (track % 5 == 0) {
                        publisher.state(track % 10 == 0 ? "paused" : "playing");
                    }
                    nextTrack = now + 5_000_000_000L;
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
                    if (first.get(key) instanceof Number a && last.get(key) instanceof Number b) {
                        summary.put(key + "Growth", b.longValue() - a.longValue());
                        boolean count = !key.endsWith("Bytes");
                        grew |= count ? b.longValue() - a.longValue() > 2 : b.longValue() - a.longValue() > 16L << 20;
                    }
                }
                long cpu = ((Number) last.get("cpuMillis")).longValue() - cpuAtStart;
                double wall =
                        ((Number) last.get("minute")).doubleValue() - ((Number) first.get("minute")).doubleValue();
                summary.put("cpuPercentOfOneCore", wall <= 0 ? -1 : Math.round(cpu / (wall * 600.0) * 1000) / 1000.0);
            }
            summary.put("status", grew ? "fail" : "pass");
            System.out.println(Json.write(summary));
            return grew ? 1 : 0;
        } finally {
            orpheus.stop();
            publisher.close();
        }
    }
}
