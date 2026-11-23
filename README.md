# lol.trq.orpheus

[![CI](https://github.com/trq-lol/lol.trq.orpheus/actions/workflows/ci.yml/badge.svg)](https://github.com/trq-lol/lol.trq.orpheus/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/java-17%2B-informational)](https://github.com/trq-lol/lol.trq.orpheus#requirements)
[![Systems](https://img.shields.io/badge/systems-Windows%20%7C%20Linux%20%7C%20macOS-informational)](docs/platforms.md)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A music library for Java: what is playing, playback controls and synced lyrics, from any source.

It reads the media sessions of the operating system (Windows, Linux, macOS), so it works with any player and needs
no account, and it accepts data pushed in from your own player. Everything a source can do is a capability you ask
for at run time: playback state, controls, queue, devices, library, lyrics, cover colours. Nothing is tied to one
music service, nothing touches the network, and unsupported operations answer "unsupported" instead of throwing.

## Requirements

| | |
|---|---|
| Java | 17 or newer. No dependencies. |
| Windows | 10 version 1809 or newer, or 11, 64-bit. Uses a small native library bundled in the jar. |
| Linux | A session bus with MPRIS players. Pure Java. |
| macOS | Experimental. Uses a bundled helper process; see [docs/platforms.md](docs/platforms.md). |

On Java 24 and newer the JVM warns about native access on Windows unless it is started with
`--enable-native-access=ALL-UNNAMED`.

## Install

No release has been published yet. The artifacts will be on GitHub Packages:

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
    implementation 'lol.trq:orpheus-core:0.1.0'
    implementation 'lol.trq:orpheus-system:0.1.0' // Windows, Linux and macOS sessions
}
```

| Artifact | What it holds |
|---|---|
| `orpheus-core` | Model, hub, push source, covers, lyrics. Pure Java. |
| `orpheus-system` | `SystemSessions.create()`: the right source for the system it runs on. Pulls in the three below. |
| `orpheus-windows` | Windows media sessions. |
| `orpheus-linux` | MPRIS players over a built-in D-Bus client. |
| `orpheus-macos` | macOS Now Playing (experimental). |

## Usage

### Show what is playing

```java
Orpheus orpheus = Orpheus.builder().source(SystemSessions.create()).build();
orpheus.start();

// any thread, every frame, no allocation
Playback now = orpheus.playback();
if (now.isPresent()) {
    draw(now.track().title(), now.track().artistLine());
    drawBar(now.timeline().progressAt(System.nanoTime()));
}
```

The position is stored as "where it was, when, and how fast it moves", so progress is computed locally and stays
smooth between updates.

### React to changes

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

Listeners run on a library thread, in order, or on the executor given to `Orpheus.builder().executor(...)`.

### Control playback

```java
Playback now = orpheus.playback();
if (now.can(Control.SEEK)) {
    orpheus.controls().seek(Duration.ofSeconds(30)).thenAccept(result -> {
        if (!result.done()) {
            log(result.status() + " " + result.reason());
        }
    });
}
orpheus.controls().togglePlayPause(); // never throws
```

Every command returns a future that always completes with `DONE`, `UNSUPPORTED`, `REFUSED` or `FAILED`.
`orpheus.controls(playback)` binds commands to the session of a snapshot, so a click cannot land on a different
player after the selection moved.

### Synced lyrics from your own files

```java
Orpheus orpheus = Orpheus.builder()
        .source(SystemSessions.create())
        .lyrics(LocalLyrics.in(Path.of("lyrics")))
        .build();

Snapshot snapshot = orpheus.snapshot(); // playback, lyrics and cover that belong together
int line = snapshot.lyrics().lineAt(snapshot.playback().timeline().positionMillisAt(System.nanoTime()));
if (line >= 0) {
    draw(snapshot.lyrics().line(line).text());
}
```

`LocalLyrics` reads `.lrc` files (line and word timing, translations, offsets) and lyrics embedded in MP3, FLAC and
MP4 files. Lyrics are shown only for a confident match; a wrong match is worse than none. The library ships no
online lyrics provider: lyrics are copyrighted, and `LyricsProvider` is the place to plug in a source you have the
right to use.

### Push your own player in, and choose what is visible

```java
PushSource mine = PushSource.create("my-player");
Orpheus orpheus = Orpheus.builder()
        .source(mine)
        .source(SystemSessions.create())
        .apps(AppRules.defaults().allow("firefox").deny("vlc"))
        .build();

mine.handler(Control.PAUSE, request -> player.pause());
mine.publish(PlaybackUpdate.playing(track).position(Duration.ofSeconds(12)));
orpheus.pin(mine.id());   // show only this source; orpheus.unpin() returns to choosing automatically
```

When several sessions exist, the playing one is chosen; a short pause does not flip the display to another player.
Two sources that describe the same song are merged, and two different songs are never mixed.

## Privacy

Operating system sessions include browser tabs (private windows too), video players and calls, and the media kind
they report cannot be trusted: browsers label everything as music.

- By default only well-known dedicated music players are visible. Browsers and unknown applications stay hidden
  until you allow them with `AppRules`. `orpheus.sessions()` lists hidden applications by id, without track data, so
  a program can offer "allow this app".
- `Playback.shareable()` is false until you opt a source in. The library itself never sends anything anywhere.
- No network access, no telemetry, no files written except the extracted native code, and no track data in logs
  unless you install a debug sink.
- What cannot be told apart: a music video from a song in a browser, and a private window from a normal one.
- On Linux a player names its cover as a file path. The library reads that file only if it is a regular file that
  starts like an image, up to 16 MiB, and hands the picture to your program as the cover. A program on the session
  bus that poses as a player can therefore make an image file of the user show up as a cover.

## Limitations

- Windows gives no per-application volume through its media session interface, so volume and mute are unsupported
  there.
- MPRIS has no media kind and no fast-forward or rewind commands.
- macOS support rests on a private part of the system and can stop working with any macOS update. It only sees the
  one application the system has elected, and it is marked experimental.
- Queue, devices and library are designed and work for push sources. No operating system source exposes them. A
  source for a music service API is designed but not part of this version.
- A track that repeats with the same title and artist is seen as a seek to the start, not as a new track.

## Verification

- Unit tests cover selection, merging, position arithmetic, lyric timing and parsing, hostile input for every
  parser, the D-Bus codec and the child-process handling. The important ones are checked by breaking the code on
  purpose.
- A live harness publishes generated tracks through each system's real media session interface and checks what the
  library sees, every control round trip, filtering, a crashing player, source switching, lyrics timing and
  start/stop leaks. See [docs/harness.md](docs/harness.md) and the numbers in [BENCHMARKS.md](BENCHMARKS.md).
- The jars are also run after being repackaged, obfuscated and downgraded to Java 8 bytecode, on Java 8, 17 and 25.

## Building and testing

```sh
./gradlew spotlessCheck build        # all modules; cargo builds the Windows library when run on Windows
./gradlew :core:jmh                  # benchmarks
./gradlew :harness:installDist       # then: harness/build/install/harness/bin/harness live
```

## For AI agents

Start with [CLAUDE.md](CLAUDE.md) for the mental model and the traps, then [llms.txt](llms.txt) for an index of
every doc and core source file. The tests under `core/src/test` are the executable examples of the API, and the
harness under `harness/` shows real use against a live session.

## License

MIT, see [LICENSE](LICENSE). Third-party parts are listed in [NOTICE.md](NOTICE.md). This project is not affiliated
with any operating system vendor, player or music service; see [DISCLAIMER.md](DISCLAIMER.md).
