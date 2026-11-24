# Live harness

Tests the library against the real media sessions of the system, using a test player with generated tracks. It is not
published.

| System | Test player |
|---|---|
| Windows | `native/publisher-windows` (Rust) |
| Linux | `MprisPublisher` (Java) |
| macOS | `native/publisher-macos` (Objective-C) |

## Running

```sh
./gradlew :harness:installDist
(cd native && cargo build --release --target x86_64-pc-windows-msvc)   # Windows
sh native/publisher-macos/build.sh                                     # macOS

H=harness/build/install/harness/bin/harness
$H live                                     # all scenarios
$H live --only controls                     # one scenario
$H watch --all                              # print what real players report
$H soak --minutes 60                        # resource use; JAVA_OPTS=-Xint leaves out compiler memory
$H soak --idle --minutes 10
$H soak --minutes 15 --track-millis 100     # growth per track; add --no-covers
```

Run from the repository root. In a headless Linux shell, prefix commands with `dbus-run-session --`.

Each scenario prints `pass`, `fail` or `skip` as JSON. A skip is not a pass. The exit code is 1 if any scenario failed.

## Scenarios

| Name | Checks |
|---|---|
| `metadata` | Title, artist, album, length, state, app id |
| `change_latency` | 30 state changes, time until visible |
| `cover` | Cover colour, and no cover when there is none |
| `progress` | Position while playing, after a seek, while paused |
| `controls` | Every control reaches the player and shows its effect |
| `unsupported_controls` | A withdrawn control returns `UNSUPPORTED` |
| `rapid_changes` | 40 quick track changes never mix two tracks |
| `text` | CJK, right-to-left, emoji, very long titles |
| `missing_metadata` | A track with only a title |
| `filtering` | Hidden by default, allow, deny, video |
| `player_crash` | A killed player disappears and can return |
| `source_switching` | Two sources, sticky selection, pinning |
| `lyrics` | Local lyrics match and change on time |
| `start_stop` | 100 cycles without leaks |
