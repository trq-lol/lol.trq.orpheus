# Contributing

- Conventional Commits, imperative, lowercase subject, no trailing period. No AI-attribution trailers.
- `./gradlew spotlessApply` then `./gradlew spotlessCheck build` before pushing. Rust code: `cargo fmt` and
  `cargo clippy -- -D warnings` in `native/`.
- Bytecode stays at release 17, and published modules stay free of dependencies.
- The library is general purpose. Never name a consuming application in code, docs, comments, tests or logs. No
  machine paths, secrets, real music, lyrics or cover art: tests generate their media.
- A test must be able to catch a bug that could really happen. No tests of getters, records, the JDK or of fakes.
  Use fake sources and a fake clock, and wait on events instead of sleeping.
- Input from outside (players, files, the bus, child processes) is untrusted. A parser gets size limits and a test
  with malformed input.
- Nothing may block the caller, throw for an unsupported operation, or outlive `stop()`. Changes to a source are
  checked with the live harness (see [docs/harness.md](docs/harness.md)) and the result goes in the pull request.
- A change to a hot path (snapshot read, progress, lyric lookup) comes with a `./gradlew :core:jmh` run before and
  after, and updated numbers in `BENCHMARKS.md` if they moved.
- Classes may be relocated, obfuscated and downgraded by consumers: no `ServiceLoader`, no reflection on the
  library's own names, Unix sockets only through reflection and no `ProcessHandle`. See
  [docs/platforms.md](docs/platforms.md#transforming-the-jars).
