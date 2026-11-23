/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import lol.trq.orpheus.AppRules;
import lol.trq.orpheus.Orpheus;
import lol.trq.orpheus.Playback;
import lol.trq.orpheus.event.CoverReady;
import lol.trq.orpheus.event.PlaybackChanged;
import lol.trq.orpheus.event.SessionsChanged;
import lol.trq.orpheus.event.SourceStatusChanged;

/**
 * Development harness. Prints one JSON object per line.
 *
 * <pre>
 * live  [--publisher path] [--only a,b] [--out file]   run every scenario against a generated session
 * watch [--seconds n] [--allow app,...] [--only app,...] [--all] [--send pause,play,...]
 *                                                       print what the system reports (for trying real players)
 * soak  [--minutes n] [--sample seconds] [--idle] [--cache-bytes n] [--publisher path]
 *                                                       run for a while and report resource use
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
                options.put(args[i].substring(2), hasValue ? args[++i] : "true");
            }
        }
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        String command = args.length == 0 ? "" : args[0];
        int exit =
                switch (command) {
                    case "live" -> live(options);
                    case "watch" -> watch(options);
                    case "soak" -> Soak.run(options);
                    default -> {
                        System.err.println("usage: live | watch | soak (see the class comment)");
                        yield 2;
                    }
                };
        System.exit(exit);
    }

    static Map<String, Object> environment() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        env.put("arch", System.getProperty("os.arch"));
        env.put("java", System.getProperty("java.version"));
        env.put("processors", Runtime.getRuntime().availableProcessors());
        return env;
    }

    private static int live(Map<String, String> options) throws Exception {
        List<String> lines = new ArrayList<>();
        Rig rig;
        try {
            rig = Rig.detect(options.containsKey("publisher") ? Path.of(options.get("publisher")) : null);
        } catch (Exception e) {
            emit(lines, Map.of("scenario", "*", "status", "skip", "detail", String.valueOf(e.getMessage())));
            write(options, lines);
            return 0;
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("rig", rig.name());
        header.putAll(environment());
        emit(lines, header);
        List<String> only =
                options.containsKey("only") ? List.of(options.get("only").split(",")) : List.of();
        Scenarios scenarios = new Scenarios(rig);
        int failed = 0;
        int passed = 0;
        int skipped = 0;
        try {
            scenarios.open();
        } catch (Exception e) {
            emit(lines, Map.of("scenario", "*", "status", "skip", "detail", "no publisher: " + e.getMessage()));
            write(options, lines);
            return 0;
        }
        try {
            for (Map.Entry<String, Scenarios.Body> scenario : scenarios.all().entrySet()) {
                if (!only.isEmpty() && !only.contains(scenario.getKey())) {
                    continue;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("scenario", scenario.getKey());
                Map<String, Object> numbers = new LinkedHashMap<>();
                long start = System.nanoTime();
                try {
                    scenario.getValue().run(numbers);
                    result.put("status", "pass");
                    passed++;
                } catch (Scenarios.Skip skip) {
                    result.put("status", "skip");
                    result.put("detail", skip.getMessage());
                    skipped++;
                } catch (Scenarios.Failure failure) {
                    result.put("status", "fail");
                    result.put("detail", failure.getMessage());
                    failed++;
                } catch (Exception e) {
                    result.put("status", "fail");
                    result.put("detail", e.toString());
                    failed++;
                }
                result.put("seconds", Math.round((System.nanoTime() - start) / 1e7) / 100.0);
                result.putAll(numbers);
                emit(lines, result);
            }
        } finally {
            scenarios.close();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("summary", rig.name());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        emit(lines, summary);
        write(options, lines);
        return failed == 0 ? 0 : 1;
    }

    private static void emit(List<String> lines, Map<String, Object> object) {
        String line = Json.write(object);
        lines.add(line);
        System.out.println(line);
    }

    private static void write(Map<String, String> options, List<String> lines) throws Exception {
        if (options.containsKey("out")) {
            Files.write(Path.of(options.get("out")), lines);
        }
    }

    private static int watch(Map<String, String> options) throws Exception {
        Rig rig = Rig.detect(null);
        AppRules rules = options.containsKey("all") ? AppRules.all() : AppRules.defaults();
        if (options.containsKey("only")) {
            rules = AppRules.none().allow(options.get("only").split(","));
        }
        if (options.containsKey("allow")) {
            rules = rules.allow(options.get("allow").split(","));
        }
        Orpheus orpheus = Orpheus.builder()
                .source(rig.source())
                .apps(rules)
                .debug(options.containsKey("debug") ? System.err::println : null)
                .build();
        orpheus.listen(event -> {
            Map<String, Object> line = new LinkedHashMap<>();
            if (event instanceof SourceStatusChanged status) {
                line.put("status", status.status().state().toString().toLowerCase(Locale.ROOT));
                line.put("reason", status.status().reason());
            } else if (event instanceof SessionsChanged sessions) {
                List<Object> list = new ArrayList<>();
                for (var session : sessions.sessions()) {
                    list.add(Map.of(
                            "app",
                            session.app().id(),
                            "state",
                            session.state().toString(),
                            "visible",
                            session.visible()));
                }
                line.put("sessions", list);
            } else if (event instanceof PlaybackChanged || event instanceof CoverReady) {
                Playback p = event.snapshot().playback();
                line.put("event", event.getClass().getSimpleName());
                line.put("app", p.app().id());
                line.put("state", p.state().toString());
                line.put("title", p.track().title());
                line.put("artists", p.track().artists());
                line.put("album", p.track().album());
                line.put("durationMillis", p.track().durationMillis());
                line.put("kind", p.track().kind().toString());
                line.put("positionMillis", p.timeline().positionMillisAt(System.nanoTime()));
                line.put("rate", p.timeline().rate());
                line.put("shuffle", p.shuffle().toString());
                line.put("repeat", p.repeat().toString());
                line.put("controls", p.controls().toString());
                var cover = event.snapshot().cover();
                line.put(
                        "cover",
                        cover == null ? (p.coverUri() == null ? "none" : "uri") : cover.width() + "x" + cover.height());
            } else {
                return;
            }
            System.out.println(Json.write(line));
        });
        orpheus.start();
        long seconds = Long.parseLong(options.getOrDefault("seconds", "30"));
        if (options.containsKey("send")) {
            // --send pause,play,next: one command per second, starting three seconds in
            LockSupport.parkNanos(3_000_000_000L);
            seconds = Math.max(0, seconds - 3);
            for (String name : options.get("send").split(",")) {
                var controls = orpheus.controls();
                var future =
                        switch (name) {
                            case "pause" -> controls.pause();
                            case "play" -> controls.play();
                            case "toggle" -> controls.togglePlayPause();
                            case "next" -> controls.next();
                            case "previous" -> controls.previous();
                            case "stop" -> controls.stop();
                            case "seek" -> controls.seek(java.time.Duration.ofSeconds(20));
                            case "shuffle" -> controls.shuffle(true);
                            case "repeat" -> controls.repeat(lol.trq.orpheus.Repeat.ALL);
                            case "rate" -> controls.rate(1.5);
                            case "volume" -> controls.volume(0.5);
                            default -> controls.mute(true);
                        };
                var result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                System.out.println(Json.write(
                        Map.of("sent", name, "result", result.status().toString(), "reason", result.reason())));
                LockSupport.parkNanos(1_000_000_000L);
                seconds = Math.max(0, seconds - 1);
            }
        }
        LockSupport.parkNanos(seconds * 1_000_000_000L);
        orpheus.stop();
        return 0;
    }
}
