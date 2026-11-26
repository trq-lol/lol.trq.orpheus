# CLAUDE.md: working in lol.trq.orpheus

A general-purpose Java 17 library for "what is playing", playback controls and synced lyrics, from operating system
media sessions and from data pushed in. MIT. Rules for contributors are in [CONTRIBUTING.md](CONTRIBUTING.md); an
index of files is in [llms.txt](llms.txt).

## The 30-second mental model

```
sources ──► SourceSink ──► Engine (one thread) ──► volatile Snapshot ──► any thread reads
 WindowsSessions            selection, merge,        Playback + Lyrics + Cover
 MprisSource                position anchoring       events ──► listeners (own thread / your executor)
 MacNowPlaying
 PushSource      ◄── Transport.send ◄── Controls ◄── orpheus.controls()
```

- A `Source` reports its whole session list every time (`SessionUpdate`), from its own thread. It never blocks.
- `Engine` (in `core/.../internal`) is a plain state machine with no threads and no clock of its own. `Orpheus`
  wraps it with the `orpheus-hub`, `orpheus-events` and `orpheus-io` threads. Test logic against `Engine` with a fake
  clock; test threading against `Orpheus`.
- A `Timeline` is (position, nanoTime anchor, rate, running). Readers compute the position themselves.
- Capabilities are interfaces a source hands out (`Transport`, `QueueAccess`, `Devices`, `LibraryAccess`). Missing
  ones make the facade answer `UNSUPPORTED`.

## The patterns that matter

- Everything public is immutable or thread-safe, with one stated exception: `Cover.rgba()` and `palette()` hand
  out the cached arrays, which callers must not modify. `orpheus.snapshot()` is one volatile read.
- A report is trusted for its position when it differs from the extrapolation by more than 100 ms; otherwise the old
  anchor is kept so the bar does not jitter.
- Selection is sticky: the selected session keeps its place for 3 s after it stops playing.
- Merging is by `Track.sameSong`, only across different sources, and ends the moment the songs differ.
- Windows: one Rust worker thread owns all WinRT objects; Java blocks in a native `await` and gets byte blobs
  (`Blob`). Native code never calls into Java. The class and method are bound through a system property handshake.
- Linux: `Bus` has one reader thread; `MprisSource` does everything else on its single worker thread. `NativeMpris`
  uses the Windows bridge design (`NativeBridge`, `NativeBlob`, ABI 1) over `native/orpheus-linux` (zbus, blocking):
  one worker owns the players, reader threads post signals, helper threads make calls that could hang.
- macOS: one long-lived child process streams JSON lines; commands are short-lived child processes.

## Pitfalls to avoid

- Do not add `ServiceLoader`, `Class.forName` on the library's own classes, or string constants that look like its
  package names. Consumers relocate and obfuscate the jars.
- Do not call `SocketChannel.open(ProtocolFamily)` or `UnixDomainSocketAddress` directly, and do not use
  `ProcessHandle` at all in published modules: bytecode downgraders replace those call sites with stubs that fail.
  Use reflection as `Bus.openUnix` does.
- Do not add a second native method to `Bridge` without registering it in `JNI_OnLoad`, and bump the ABI number on
  both sides when the blob layout changes.
- Do not log titles or application ids outside the debug sink.
- Do not let a parser throw on bad input: skip, cap, or return "nothing".
- Do not use `CompletableFuture.orTimeout` or other JDK-global helper threads: nothing may outlive `stop()`.
- A lyrics match below `LyricsMatcher.ACCEPT` must show nothing.

## Conventions

- Java source starts with `/* SPDX-License-Identifier: MIT */`. Format with `./gradlew spotlessApply`.
- Commits use Conventional Commits. **You own every commit, including code an AI wrote. Never add Co-Authored-By or
  any other AI-attribution trailer.**
- Never name a consuming application. No machine paths or secrets. Tests generate their media.
- `internal` packages are not API and may change in any release.
