# Changelog

Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.1] - 2026-11-27

### Fixed

- Linux: a player that changed right after it was found could show its previous track or state until its next change.
  Property reads that a newer signal overtook are now asked again, in both `MprisSource` and `NativeMpris`.

## [0.4.0] - 2026-11-26

### Added

- `NativeMpris`: Linux MPRIS through a bundled Rust library (x64), with the same sessions, ids and controls as
  `MprisSource`. It needs no system library but the C library. `SystemSessions` uses it on Linux and falls back to
  `MprisSource` when it cannot load.

### Changed

- Readable application names: Windows shows `Spotify Music` for a packaged app, not its app user model id, and a
  Linux player without an MPRIS identity is shown as its capitalized bus name.

## [0.3.0] - 2026-11-24

### Added

- `BpmProvider` and `Snapshot.bpm()`.

## [0.2.0] - 2026-11-24

### Fixed

- macOS: an update that the system delivers late no longer sets the position back by half a second.

## [0.1.1] - 2026-11-24

### Fixed

- `orpheus-spotify`: a missing scope no longer rejects a valid token.

## [0.1.0] - 2026-11-24

### Added

- Windows: the session list is checked every 30 seconds, for when Windows stops reporting changes.
- macOS: the controls a player has enabled, and its shuffle and repeat state.
- `orpheus-spotify`: an experimental source for the Spotify Web API.
- `Source.changedElsewhere`, so a polling source can react to the system session.
- Playback state, controls, events and immutable snapshots (`Orpheus`).
- Windows media sessions, Linux MPRIS and experimental macOS Now Playing.
- `PushSource` for your own player.
- Automatic source selection, `pin` and `sources()`.
- `AppRules` to choose which apps are visible.
- Covers as RGBA with average colour and palette.
- Synced lyrics from `.lrc` files and from MP3, FLAC and MP4 tags.
