# Benchmarks

Three kinds of numbers: JMH microbenchmarks of the code paths, the live harness against a real operating system
session, and long runs that watch resource use. Every number here was measured; where a target was missed or could
not be measured, it says so.

## How to run

```sh
./gradlew :core:jmh :linux:jmh :windows:jmh     # add -Pjmh.include=ReadBench to pick one class
harness/build/install/harness/bin/harness live
harness/build/install/harness/bin/harness soak --minutes 60
harness/build/install/harness/bin/harness soak --idle --minutes 10
```

JMH results are written to `<module>/build/results/jmh/results.json`: one fork, three one-second warm-up iterations,
five one-second measurements, average time, with the allocation profiler. See [docs/harness.md](docs/harness.md) for
the live scenarios.

## Code paths (JMH)

Intel Core i7-12700K, Windows 10, Java 25.0.3. Time per operation and bytes allocated per operation.

### What a render loop does every frame

| Benchmark | What | Time | Allocated |
|---|---|---|---|
| `ReadBench.progress` | read the snapshot, compute the progress fraction | 2.2 ns | 0 B |
| `ReadBench.currentLine` | current lyric line of 80 at the playing position | 5.7 ns | 0 B |
| `ReadBench.currentWord` | current word inside the current line | 10.1 ns | 0 B |
| `ReadBench.frame` | title, artist line, a control check, lyric line and progress | 9.0 ns | 0 B |

### What happens when a source reports

| Benchmark | What | Before | After | Allocated before | Allocated after |
|---|---|---|---|---|---|
| `EngineBench.unchangedReport` | a report identical to the last one | 2759 ns | 1.3 ns | 6864 B | 0 B |
| `EngineBench.changedReport` | a state change with two sources merged: new snapshot, two events | 2790 ns | 1585 ns | 7048 B | 4288 B |

"Before" re-normalized titles, artists and application ids on every report. "After" skips a report that equals the
previous one and caches the comparison keys.

### Once per track, off the reading threads

| Benchmark | What | Time | Allocated |
|---|---|---|---|
| `LyricsBench.parseLrc` | parse an 80-line `.lrc` file | 13.1 µs | 75 kB |
| `LyricsBench.matchScore` | score one lyrics candidate against a track | 3.6 µs | 3.8 kB |
| `LyricsBench.decodeCover` | decode a 640x640 PNG, scale to 512, compute the palette | 7.8 ms | 6.9 MB |

### Talking to the system

| Benchmark | What | Time | Allocated |
|---|---|---|---|
| `NativeBench.nativeCall` | one call into the Windows library and back | 61 ns | 24 B |
| `NativeBench.readSessionsBlob` | read two sessions from the library's reply | 141 ns | 976 B |
| `WireBench.decodePropertiesChanged` | decode an MPRIS property change with metadata | 959 ns | 4.8 kB |
| `WireBench.encodeCall` | encode an MPRIS method call | 2025 ns | 2.5 kB |

## Targets, measured live

From `harness live` with the stand-in players. Windows: the same machine, Windows 10 Pro 19045. Linux: Ubuntu 24.04
under WSL 2 on the same machine, Java 21, the session bus of the user session. macOS: see below.

| Target | Windows | Linux | Result |
|---|---|---|---|
| A change is visible within 250 ms | median 0.9 ms, worst 1.4 ms of 30 | median 0.6 ms, worst 2.4 ms of 30 | met |
| A control round trip within 300 ms | 0.2 to 0.5 ms; first call 4.2 ms | 0.3 to 0.6 ms; first call 7.2 ms | met |
| Position error under 100 ms while playing | 0.7 ms worst of 20 samples | 1.4 ms | met |
| Position error under 100 ms after a seek | 0.3 ms | 1.4 ms | met |
| Lyric line change within 50 ms | 0.3 ms worst of 4 lines | 0.4 ms | met |
| No allocation for per-frame progress and lyric lookup | 0 B (JMH, above) | same code | met |
| 100 start/stop cycles without growth | threads 49 to 49, handles 522 to 521 | threads 29 to 30, descriptors 19 to 19, no child processes | met |

The latency figures are measured from just before the stand-in player is told to change until the snapshot shows the
change, so they include the player's own work and the trip through the operating system. They say what the library
adds, not how fast real players are: a real player decides when it tells the system, and many update the position
only every few seconds, which is why the library extrapolates.

Other live results on both systems: every control the platform has reaches the player and its effect is reflected; a
withdrawn control completes `UNSUPPORTED` without reaching the player; 40 track changes in a burst never produce a
snapshot mixing two tracks; CJK, right-to-left and emoji text arrive unchanged and a 3000-character title is capped at
1024; a killed player disappears (Windows 11 ms, Linux 1 ms) and a command to it is refused; the selection moves to
another playing source 3.0 s after the selected one pauses; hidden-by-default, allow, deny and (Windows) video rules
hold.

## Long runs

Pending: this section is filled in from `harness soak` runs.

## macOS

Partly working: 10 of 14 live scenarios passed on a GitHub macOS 15.8.2 arm64 runner (Java 25, 2026-11-23, one run)
with the test publisher. No real player was tried.

| Scenario | Result |
|---|---|
| metadata, rapid changes, text, missing metadata, filtering, source switching, start/stop | pass |
| track change latency | pass, median 10.5 ms, worst 29.7 ms |
| cover | pass, 25.5 ms |
| player crash | pass, noticed after 24.9 ms |
| progress | FAIL: position 573 ms off while playing, target 100 ms; 1.0 ms off after a seek |
| lyrics | FAIL: a line changed 566 ms off, target 50 ms |
| controls | FAIL: a shuffle change was not reflected |
| unsupported controls | FAIL: a withdrawn control stayed offered; the helper does not report control flags |

The causes of the first three failures have not been investigated.

## Real players

Windows 10 Pro 19045, 2026-11-23, with `harness watch --only <app> --send ...`. The browsers played a generated tone
from a local page that sets Media Session metadata, artwork, position state and action handlers.

| Player | Seen | Text | Cover | Position | Controls that worked | Notes |
|---|---|---|---|---|---|---|
| Chrome 157 | yes, `chrome.exe` | title, artist, album | yes | yes, also after seek | play, pause, toggle, seek, next, previous | Reports kind music for everything. No shuffle, repeat or rate. |
| Chrome 157, incognito | yes, `chrome.exe` | placeholder title only | a generic icon | yes | same | A private window still publishes a session; only the text and art are withheld. |
| Firefox 160 | yes, `firefox.exe` | title, artist (no album) | yes | yes, one 1 s correction seen | same | |
| Edge 158 | yes, `msedge.exe` | title, artist (no album) | none within 12 s | yes | same | |
| VLC 3.0.20 | no | | | | | Publishes no media session on Windows. |

Not tested: Spotify, TIDAL, Apple Music and other dedicated players (not installed on the test machine, and signing
in to an account was out of scope), and the Windows Media Player app (it could not be started unattended).

Browsers do not refresh the position when playback pauses or resumes. The library carries the position across such a
state change itself; before that was added, a paused Chrome tab showed the position of its last report.
