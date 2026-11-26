# Platforms

| | Windows | Linux | macOS (experimental) |
|---|---|---|---|
| Source class | `WindowsSessions` | `MprisSource` or `NativeMpris` | `MacNowPlaying` |
| Interface | Media sessions | MPRIS | Now Playing, through a helper |
| Sessions | One per app | One per player | Only the app macOS picked |
| Text and length | Yes | Yes | Yes |
| Cover | Yes | `file:` and `data:`; web URLs are passed on unchecked | Yes |
| Media kind | Browsers always say music | No | Weak hint |
| Play, pause, toggle, stop, next, previous, seek | Yes | Yes | Yes |
| Shuffle, repeat | Yes | Yes | Yes |
| Rate | Yes | Yes | No |
| Fast-forward, rewind | Yes | No | No |
| Volume | No | Yes | No |
| Native code | Rust library | None, or a Rust library for `NativeMpris` | Helper script and framework |

Controls also depend on the player; check `Playback.can`.

## App ids

`AppRules` matches against `SourceApp.id()`:

| System | Id | Example |
|---|---|---|
| Windows | Executable or app user model id | `Spotify.exe` |
| Linux | MPRIS name | `spotify` |
| macOS | Bundle id, or process name without one | `com.spotify.client` |

Rules match whole words in order, ignoring case. `spotify` matches all three. Apps choose their own name on Linux, so
a rule is not proof of identity.

## Windows

- `orpheus.dll` is extracted to `%LOCALAPPDATA%\orpheus\native\` and checked against its SHA-256 on every start.
- If it cannot load, the source reports `UNAVAILABLE` with the reason.
- Copies unused for 30 days are removed.
- A packaged app is named from its app user model id: `SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify` shows as
  `Spotify Music`.

## Linux

`MprisSource` uses a built-in D-Bus client over Unix sockets. On Java before 16 it reports `UNAVAILABLE`.

`NativeMpris` reads the same players through `liborpheus.so`, a Rust library bundled for x64:

- It is extracted to `$XDG_CACHE_HOME/orpheus/native/` (or `~/.cache/orpheus/native/`) and checked against its
  SHA-256 on every start.
- It links only against the C library (glibc 2.35 or newer); it has its own D-Bus client and needs no libdbus.
- Session ids, app ids and controls are the same as `MprisSource`, so pins and rules carry over.
- If it cannot load, the source reports `UNAVAILABLE` with the reason. `SystemSessions` uses `NativeMpris` and falls
  back to `MprisSource` when the library cannot load.

## macOS

macOS has no public API for this. The library runs
[mediaremote-adapter](https://github.com/ungive/mediaremote-adapter) (BSD-3-Clause) as a child process of `/usr/bin/perl`.

- Any macOS update can break it. The source then reports `UNAVAILABLE`.
- Only the app macOS picked is visible, and commands go to it.
- Position and lyrics can be half a second off after two updates within 400 ms, at any playback rate but the normal one or
  when the second one is a seek.
- A player that never registers as an application (some command-line players) is not seen.
- A play or pause change within milliseconds of another can be missed until the next change.
- The helper has to be built on a Mac with `native/macos/build-adapter.sh`. `MacNowPlaying.create(Path)` uses your own
  build.

## Transforming the jars

The jars can be relocated, obfuscated and downgraded without keep rules. Keep the `orpheus-native/` resources.
`./gradlew :harness:compat` tests this on Java 8, 17 and 25.
