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

`harness soak`, one run each, 2026-11-23: 60 minutes with a new track every 5 s, pauses, and a position read about
every 16 ms; then 10 minutes idle. Cover cache limited to 1 MiB. Windows 10 (Java 25) and Ubuntu 24.04 in WSL (Java
21), both at once on one PC that was also in other use. The harness fails a run whose last sample exceeds its first by
more than 2 threads or handles or 16 MiB.

**Both 60-minute runs FAILED that check.**

| 60 minutes | Windows | Linux |
|---|---|---|
| Java threads, first to last sample | 12 to 12 | 12 to 12 |
| OS threads | 39 to 35 | 28 to 27 |
| Handles / file descriptors | 434 to 450 (limit +2: FAIL) | 20 to 20 |
| Resident memory | 58.8 to 74.9 MiB, +16.06 (limit +16: FAIL) | 70.7 to 99.8 MiB, +29.1 (limit +16: FAIL) |
| Private memory | 159.5 to 123.4 MiB | not measured |
| Java heap in use, minute 10 to 55 | 3.5 to 3.5 MB | 3.5 to 3.5 MB |
| CPU of one core, whole process | 0.17% | 0.32% |

The first sample is taken at minute 0, before the JVM has warmed up. From minute 10 to 55, Windows handles went from
450 to 450 and its resident memory grew 6.4 MiB; Linux resident memory grew 17.3 MiB, 8.6 of it between minutes 45 and
50. Java heap and threads stayed flat, so any growth is outside the Java heap. Its cause is not known, and a slow
native leak is not ruled out. A longer run would be needed to tell.

| 10 minutes idle | Windows | Linux |
|---|---|---|
| CPU of one core, whole process (target under 0.1%) | 0.01%, met | 0.144%, MISSED |
| Growth check | pass | pass |

A separate 60 s reading of the threads of the Linux idle process gave 0.19% for the whole process, of which the named
library threads used about 0.02%. Most of the rest was the JVM itself (compiler, periodic tasks, garbage collector).

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

On a Mac with an M2 and macOS 26.5 (Java 25, 2026-11-23) the same 10 of 14 pass: progress 532 ms off while
playing and 1.6 ms after a seek, lyric lines 524 ms off, track changes median 3.5 ms and worst 5.2 ms, cover 5.9 ms.

- Progress and lyrics: in tests on that Mac, an update of the now playing data that followed the previous one within
  0.5 s arrived 510 to 620 ms late. It carried the late delivery time as its timestamp and the position from before the
  delay, so it could not be told from a correct one. At 0.8 s or more after the previous update there was no delay (at
  most about 40 ms). The harness starts playback right after the track, so it meets this every time. The target is
  kept and reported as missed. That the lyrics failure has the same cause is likely but was not measured.
- The test publisher also resent an outdated position on every state change. That is corrected; with the correction the
  result was still 10 of 14.
- Shuffle: the helper stream never carried shuffle or repeat state, so both stay unknown. The commands themselves work.
- Withdrawn controls: the stream sent nothing when the player disabled a control.

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
