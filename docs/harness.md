# The live harness

Development only; not part of any published artifact. It checks the library against the real media session
interface of the system it runs on, with a stand-in player it controls:

| System | Stand-in player | How it publishes |
|---|---|---|
| Windows | `native/publisher-windows` (Rust) | A system media transport controls session of its own |
| Linux | `MprisPublisher` (Java) | An MPRIS service on the session bus |
| macOS | `native/publisher-macos` (Objective-C) | The Now Playing info centre |

All tracks, covers and lyrics are generated. Nothing is played aloud.

## Running

```sh
./gradlew :harness:installDist
(cd native && cargo build --release --target x86_64-pc-windows-msvc)   # Windows publisher
sh native/publisher-macos/build.sh                                     # macOS publisher

harness/build/install/harness/bin/harness live                  # every scenario, JSON lines on stdout
harness/build/install/harness/bin/harness live --only controls  # one scenario
harness/build/install/harness/bin/harness watch --all           # print what real players report
harness/build/install/harness/bin/harness soak --minutes 60     # resource use over time
harness/build/install/harness/bin/harness soak --idle --minutes 10
```

On Linux the harness needs a session bus; in a headless shell use `dbus-run-session -- harness live`.

A scenario prints `pass`, `fail` with the reason, or `skip` when the source cannot work on the machine. A skip is
never counted as a pass. The exit code is 1 when any scenario failed.

## Scenarios

| Name | What it checks |
|---|---|
| `metadata` | Title, artist, album, length, state and application id arrive |
| `change_latency` | Time from telling the player to change until the snapshot shows it (30 changes) |
| `cover` | The cover decodes to the published colour, and a track without one has none |
| `progress` | Position error while playing, after a seek, and that it stands still while paused |
| `controls` | Every control the platform has reaches the player, with round-trip times, and its effect is reflected |
| `unsupported_controls` | A withdrawn control is no longer offered and completes `UNSUPPORTED` without reaching the player |
| `rapid_changes` | 40 track changes in a burst: the last one wins and no snapshot mixes two tracks |
| `text` | CJK, right-to-left, emoji and mixed text unchanged; a very long title capped |
| `missing_metadata` | A track with only a title: no artist, no length, no cover, progress 0 |
| `filtering` | Hidden by default, visible when allowed, hidden when denied; video needs its own permission |
| `player_crash` | A killed player disappears, a command to it is refused, a restarted one is picked up |
| `source_switching` | Two playing sources, the sticky selection, pinning |
| `lyrics` | Local synced lyrics are found, the wrong artist's are not, and lines change on time |
| `start_stop` | 100 start/stop cycles without growth in threads, handles or child processes |
