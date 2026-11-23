# Platforms

What each operating system gives the library, what it cannot give, and how the native parts are handled.

## What each system provides

| | Windows | Linux | macOS (experimental) |
|---|---|---|---|
| Source class | `WindowsSessions` | `MprisSource` | `MacNowPlaying` |
| Interface | Media sessions (Windows 10 1809+) | MPRIS on the session bus | Now Playing, through a helper |
| Sessions | One per application | One per player | Only the application the system elected |
| Updates | Events | Events; position read on change and every 5 s while playing | Events from the helper |
| Text, length, cover | Yes; cover as image bytes | Yes; cover from `file:` and `data:` uris, `http` and `https` passed on as an unchecked uri | Yes |
| Media kind | Reported, but browsers always say music | Not reported | A weak hint |
| Play, pause, toggle, stop, next, previous, seek | Yes | Yes, as the player allows | Yes |
| Shuffle, repeat | Yes | Yes | Commands only; the state is not seen |
| Rate | Yes | Yes | No |
| Fast-forward, rewind | Yes | No | No |
| Volume | No | Yes | No |
| Native code | A Rust library in the jar | None | A helper script and framework in the jar |

On Windows and Linux a control is offered only while the player says it accepts it (`Playback.can`); stop is
always offered on Linux, where players have no flag for it. The macOS helper reports no such flags, so the fixed
set above is offered there. Commands never throw.

## Application ids

`SourceApp.id()` is what `AppRules` matches against:

- Windows: the executable name (`Spotify.exe`) or the app user model id of a packaged application.
- Linux: the MPRIS bus name without its prefix and instance suffix (`spotify`, `firefox`, `vlc`).
- macOS: the bundle id (`com.spotify.client`).

A rule is split into parts at dots, hyphens, underscores, slashes, spaces and exclamation marks, and matches an id
that contains those parts in a row. So `spotify` matches all three ids above.

## Native loading on Windows

- `orpheus.dll` is a jar resource with its SHA-256 in an index next to it. It is extracted to
  `%LOCALAPPDATA%\orpheus\native\`, into a directory named after the checksum, checked again on every start, and
  loaded from there. Nothing is loaded from a shared temporary folder.
- A missing, blocked, damaged or wrong-architecture library does not crash anything: the source reports
  `UNAVAILABLE` with the reason.
- Two processes can extract at the same time. Extracted copies not used for 30 days are removed.
- The JVM prints a native-access warning on Java 24 and newer unless started with
  `--enable-native-access=ALL-UNNAMED`. A future Java version may require the flag.
- All Windows calls happen on one thread owned by the native library. It is never attached to the JVM, and native
  code never calls into Java: a Java thread waits in a native call and receives data back.

## The macOS helper

macOS has no public interface for reading what another application plays. The library uses
[mediaremote-adapter](https://github.com/ungive/mediaremote-adapter) (BSD-3-Clause): a script run by the system's
`/usr/bin/perl` that loads a small framework. The library starts it as a child process, reads JSON lines from it and
stops it with the source. Commands are sent by short-lived child processes.

- It relies on a private part of macOS. Any system update can break it; the source then reports `UNAVAILABLE`.
- The helper must be built on a Mac (`native/macos/build-adapter.sh`). `MacNowPlaying.create(Path)` uses a helper
  you built yourself.
- Only the elected application is visible, and commands always go to it.

## Transforming the jars

Programs that embed the library often relocate its packages, obfuscate it, or downgrade its bytecode. The library is
written to survive that without configuration:

- Nothing is looked up by class name: no `ServiceLoader`, no `Class.forName` on its own classes.
- The Windows native method is bound at load time to whatever the class and method are called after renaming. No
  keep rules are needed.
- Resource paths (`orpheus-native/...`) and the handshake property do not look like package names, so relocation
  does not rewrite them. Do not exclude those resources.
- Unix sockets are reached through reflection and `ProcessHandle` is not used, because bytecode downgraders replace
  direct uses with stubs that fail even on a modern JVM. On a Java version without Unix sockets (before 16) the Linux source
  reports `UNAVAILABLE`.

`./gradlew :harness:compat` runs the jars after repackaging, obfuscation and a downgrade to Java 8 bytecode.

## Compatibility with real players

Measured by hand with `harness watch`; see [BENCHMARKS.md](BENCHMARKS.md#real-players) for the table and the dates.
