# Benchmarks

Measured on 2026-11-23 with an i7-12700K, Windows 10 and Java 25 unless noted.

## Summary

| Target | Windows | Linux | macOS |
|---|---|---|---|
| Live scenarios | 14 of 14 | 14 of 14 | 12 of 14 |
| Change visible within 250 ms | 1.4 ms | 2.4 ms | 5.2 ms, changes sometimes lost |
| Control round trip within 300 ms | 4.2 ms | 7.2 ms | not measured |
| Position error under 100 ms | 0.7 ms | 1.4 ms | **532 ms** after a track starts |
| Position error after a seek under 100 ms | 0.3 ms | 1.4 ms | 1.6 ms |
| Lyric line change within 50 ms | 0.3 ms | 0.4 ms | **524 ms** |
| Idle CPU under 0.1% of a core | 0.01% | **0.144%** | **0.111%** |
| 100 start/stop cycles without growth | met | met | met |
| Soak without growth | **missed** | **missed** | met |

Bold means missed. Values are the worst of one run with a test player; real players add their own delay.
Linux is Ubuntu 24.04 under WSL 2 with Java 21. macOS is 26.5 on an M2.

## Code paths

| Operation | Time | Allocated |
|---|---|---|
| Read progress | 2.2 ns | 0 B |
| Current lyric line | 5.7 ns | 0 B |
| Current word | 10.1 ns | 0 B |
| Everything one frame reads | 9.0 ns | 0 B |
| Unchanged report | 1.3 ns | 0 B |
| Changed report, two sources | 1585 ns | 4288 B |
| Parse an 80-line `.lrc` | 13.1 µs | 75 kB |
| Score one lyrics candidate | 3.6 µs | 3.8 kB |
| Decode a 640x640 cover | 7.8 ms | 6.9 MB |
| Native call (Windows) | 61 ns | 24 B |
| Read two sessions (Windows) | 141 ns | 976 B |
| Decode a property change (Linux) | 959 ns | 4.8 kB |
| Encode a method call (Linux) | 2025 ns | 2.5 kB |

## Soak

Undated runs and those of 2026-11-24 marked "failed" failed if the last sample exceeded the first by more than 2 threads or
handles, or 16 MiB. The check now compares the medians of the first and last five samples after 10 minutes: 2 threads or
handles, 16 MiB resident, 4 MiB private.

| Run | Check | Memory after start |
|---|---|---|
| Windows, 1 hour | failed: handles +16, resident +16.06 MiB | +6.4 MiB, minute 10 to 55 |
| Windows, 3 hours | failed: handles +20 | private +9.3 MiB, minute 15 to 170 |
| Linux, 1 hour | failed: resident +29.1 MiB | +17.3 MiB, minute 10 to 55 |
| Linux, 3 hours | failed: resident +19.9 MiB | +0.6 MiB, minute 45 to 175 |
| macOS, 1 hour | passed | +6.4 MiB, minute 10 to 55 |
| macOS, 3 hours | passed | +4.6 MiB, minute 15 to 170 |
| Windows, 15 minutes, 9 tracks/s | failed: handles +40, threads +3 | -1.4 MiB, minute 4 to 14 |
| Linux, 15 minutes, 9 tracks/s | failed: resident +24.4 MiB | +0.2 MiB, minute 5 to 14 |
| Windows, 1 hour, 2026-11-24 | failed: handles +49, resident +21.3 MiB | heap +10.8 MiB: cover cache filling |
| Windows, 3 hours, 2026-11-24 | failed: handles +16 | private +8.6 MiB, minute 15 to 165 |
| Windows, 1 hour, 2026-11-24, `-Xint`, new check | passed: handles -4 | private +0.1 MiB, minute 10 to 60 |

The fast runs show no growth per track. The fast runs and the 3-hour macOS run used newer code than the other undated
ones. The 3-hour, fast and dated runs used a fixed 48 MiB heap.

On Windows, private memory grows 2 to 7 MiB per hour in the soak; a plain JVM does not grow. Over 90 minutes the native
memory in use stayed within 0.8 MiB (one sample was 4 MiB higher) while the memory held by the allocator grew 3 to 4 MiB
per hour. In a 3-hour run of
the library alone the held memory was level for the last 55 minutes. Handles and threads were flat after start.

Without the JIT compiler (`-Xint`) the library does not grow. In 75 minutes side by side, private memory from minute 8 to
72: native bridge alone +0.5 MiB, library with `-Xint` +0.7 MiB, library +4.7 MiB, plain JVM -0.2 MiB. A second run of the
library with the JIT grew 0.7 MiB from minute 12 to 60, swinging by up to 1.6 MiB a minute. The growth is therefore put down
to the JIT compiler; single steps did not follow the number of compilations in a log.

## macOS

| Failing scenario | Cause |
|---|---|
| `progress` | Updates within about 0.5 s of the previous one arrive 510 to 620 ms late with a late timestamp |
| `lyrics` | Probably the same |
| `change_latency` (78 of 480 runs, helper 0.8.0) | The helper drops a play or pause change when two come about 4 ms apart |

## Real players

Windows 10, by hand.

| Player | Seen | Text | Cover |
|---|---|---|---|
| Chrome 157 | yes | title, artist, album | yes |
| Chrome 157, incognito | yes | placeholder title | generic icon |
| Firefox 160 | yes | title, artist | yes |
| Edge 158 | yes | title, artist | no |
| Spotify | yes | title, artist, album | not checked |
| VLC 3.0.20 | no session | | |

Play, pause, seek, next and previous worked in every browser. Spotify was only read, not controlled. TIDAL, Apple Music
and Windows Media Player are untested, as is every real player on Linux. On macOS, QuickTime published nothing in the one attempt made.

## Running

```sh
./gradlew :core:jmh :linux:jmh :windows:jmh
harness/build/install/harness/bin/harness live
harness/build/install/harness/bin/harness soak --minutes 60
```
