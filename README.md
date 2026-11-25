# lol.trq.orpheus

[![CI](https://github.com/trq-lol/lol.trq.orpheus/actions/workflows/ci.yml/badge.svg)](https://github.com/trq-lol/lol.trq.orpheus/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/java-17%2B-informational)](https://github.com/trq-lol/lol.trq.orpheus#requirements)
[![Systems](https://img.shields.io/badge/systems-Windows%20%7C%20Linux%20%7C%20macOS-informational)](docs/platforms.md)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A Java library that shows what is playing, controls it and displays synced lyrics.

It reads the media sessions of Windows, Linux and macOS, so it needs no account and works with any player that
publishes one. You can also feed it from your own player. Only the optional [Spotify module](docs/spotify.md) uses the
network.

## Requirements

| | |
|---|---|
| Java | 17 or newer, no dependencies |
| Windows | 10 (1809 or newer) or 11, x64 |
| Linux | A session bus with MPRIS players |
| macOS | Experimental, see [platforms](docs/platforms.md#macos) |

On Java 24 and newer, start with `--enable-native-access=ALL-UNNAMED` to avoid a warning on Windows.

## Install

Artifacts are on GitHub Packages, which needs a GitHub token with `read:packages`:

```groovy
repositories {
    maven {
        url = uri('https://maven.pkg.github.com/trq-lol/lol.trq.orpheus')
        credentials {
            username = findProperty('gpr.user') ?: System.getenv('GITHUB_ACTOR')
            password = findProperty('gpr.key') ?: System.getenv('GITHUB_TOKEN')
        }
    }
}

dependencies {
    implementation 'lol.trq:orpheus-core:0.2.0'
    implementation 'lol.trq:orpheus-system:0.2.0'
}
```

| Artifact | Contents |
|---|---|
| `orpheus-core` | Everything except the system sources. Pure Java. |
| `orpheus-system` | Picks the source for the running system. Pulls in the three below. |
| `orpheus-windows` | Windows media sessions |
| `orpheus-linux` | MPRIS |
| `orpheus-macos` | macOS Now Playing |
| `orpheus-spotify` | [Spotify Web API](docs/spotify.md), opt-in |

## Usage

### Show what is playing

```java
Orpheus orpheus = Orpheus.builder().source(SystemSessions.create()).build();
orpheus.start();

Playback now = orpheus.playback(); // any thread, no allocation
if (now.isPresent()) {
    draw(now.track().title(), now.track().artistLine());
    drawBar(now.timeline().progressAt(System.nanoTime()));
}
```

### Events

```java
Subscription subscription = orpheus.listen(event -> {
    if (event instanceof TrackChanged changed) {
        log(changed.snapshot().playback().track().title());
    } else if (event instanceof CoverReady ready) {
        Cover cover = ready.snapshot().cover();
        upload(cover.width(), cover.height(), cover.rgba());
    }
});
```

Listeners run in order on a library thread, or on the executor passed to `Orpheus.builder().executor(...)`.

### Controls

```java
if (orpheus.playback().can(Control.SEEK)) {
    orpheus.controls().seek(Duration.ofSeconds(30)).thenAccept(result -> log(result.status()));
}
orpheus.controls().togglePlayPause();
```

Commands never throw. They complete with `DONE`, `UNSUPPORTED`, `REFUSED` or `FAILED`. Use
`orpheus.controls(playback)` to keep a command on the session it was shown for.

### Lyrics

```java
Orpheus orpheus = Orpheus.builder()
        .source(SystemSessions.create())
        .lyrics(LocalLyrics.in(Path.of("lyrics")))
        .build();

Snapshot snapshot = orpheus.snapshot();
int line = snapshot.lyrics().lineAt(snapshot.playback().timeline().positionMillisAt(System.nanoTime()));
if (line >= 0) {
    draw(snapshot.lyrics().line(line).text());
}
```

For a tempo, give the builder your own `BpmProvider` and read `snapshot.bpm()`. None is included.

`LocalLyrics` reads `.lrc` files and lyrics embedded in MP3, FLAC and MP4. Only confident matches are shown. There is
no online provider; implement `LyricsProvider` to add one.

### Several sources

```java
PushSource mine = PushSource.create("my-player");
Orpheus orpheus = Orpheus.builder()
        .source(SystemSessions.create())
        .source(mine)
        .apps(AppRules.defaults().allow("firefox").deny("vlc"))
        .build();

mine.handler(Control.PAUSE, request -> player.pause());
mine.publish(PlaybackUpdate.playing(track).position(Duration.ofSeconds(12)));

orpheus.pin(mine.id()); // only this source
orpheus.unpin();        // automatic again
```

Automatic mode prefers playing over paused, then the source added first. `orpheus.sources()` lists the sources for a
picker. Sources reporting the same song are merged.

## Privacy

- Only known music players are visible by default. Browsers and unknown apps stay hidden until allowed in `AppRules`.
- Browsers report every video as music, and private windows publish sessions too. The library cannot tell them apart.
- `Playback.shareable()` is false until you opt a source in.
- It makes no network requests (except `orpheus-spotify`), sends no telemetry and logs no track data unless you add a debug sink. The only files
  it writes are its extracted native files.
- On Linux, players name their cover by file path, so a program posing as a player can have the library show any
  image file the user can read (up to 16 MiB).

## Limitations

- macOS relies on a private system API that any update can break, and its timing can be off.
  [Details](docs/platforms.md#macos).
- Windows has no volume control through media sessions.
- Queue, device and library access work for push sources and Spotify only.
- A repeated track with the same title and artist counts as a seek, not a new track.

## Building

```sh
./gradlew spotlessCheck build   # cargo builds the native library on Windows
./gradlew :core:jmh
```

See the [live harness](docs/harness.md) for testing against real sessions and [benchmarks](BENCHMARKS.md) for results.

## For AI agents

Using it from Claude Code? Install the [trq-lol plugin](https://github.com/trq-lol/claude-plugin). The rest of this
section is for working on this repository.

Read [CLAUDE.md](CLAUDE.md) first. [llms.txt](llms.txt) indexes the docs and source.

## License

MIT, see [LICENSE](LICENSE). Third-party licenses are in [NOTICE.md](NOTICE.md). Not affiliated with any system vendor,
player or music service, see [DISCLAIMER.md](DISCLAIMER.md).

## Activity

![Repobeats analytics image](https://repobeats.axiom.co/api/embed/999d34277e3169cd99a0df781bfb77c41a206370.svg "Repobeats analytics image")
