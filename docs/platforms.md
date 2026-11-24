# Platforms

| | Windows | Linux | macOS (experimental) |
|---|---|---|---|
| Source class | `WindowsSessions` | `MprisSource` | `MacNowPlaying` |
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
| Native code | Rust library | None | Helper script and framework |

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

## Linux

The source uses a built-in D-Bus client over Unix sockets. On Java before 16 it reports `UNAVAILABLE`.

## macOS

macOS has no public API for this. The library runs
[mediaremote-adapter](https://github.com/ungive/mediaremote-adapter) (BSD-3-Clause) as a child process of `/usr/bin/perl`.

- Any macOS update can break it. The source then reports `UNAVAILABLE`.
- Only the app macOS picked is visible, and commands go to it.
- Position and lyrics can be half a second off right after a track starts.
- A play or pause change within milliseconds of another can be missed until the next change.
- The helper has to be built on a Mac with `native/macos/build-adapter.sh`. `MacNowPlaying.create(Path)` uses your own
  build.

## Transforming the jars

The jars can be relocated, obfuscated and downgraded without keep rules. Keep the `orpheus-native/` resources.
`./gradlew :harness:compat` tests this on Java 8, 17 and 25.
