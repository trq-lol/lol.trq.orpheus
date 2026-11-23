# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `Orpheus`: a hub that collects sessions from sources, selects one, and offers it as an immutable `Snapshot`, as
  events and through `Controls`. Reads allocate nothing; unsupported commands complete `UNSUPPORTED`.
- `PushSource` for a program's own player, with handlers per control and optional queue, device and library
  abilities.
- `AppRules`: which applications of operating system sources are visible. The default is an allow list of dedicated
  music players.
- Covers decoded to RGBA with average colour and palette, in a cache bounded by bytes.
- Lyrics: `Lrc` parser (line and word timing, translations, offsets), `LyricsMatcher`, and `LocalLyrics` for `.lrc`
  files and lyrics embedded in MP3, FLAC and MP4 files.
- `orpheus-windows`: Windows media sessions through a bundled native library.
- `orpheus-linux`: MPRIS players through a built-in D-Bus client.
- `orpheus-macos`: experimental Now Playing support through a bundled helper process.
- `orpheus-system`: `SystemSessions.create()` picks the source for the running system.
