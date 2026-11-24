# Changelog

Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
