# Disclaimer

lol.trq.orpheus is an independent open-source project. It is not affiliated with, endorsed by or sponsored by
Microsoft, Apple, the freedesktop.org project, Spotify, TIDAL, SoundCloud or any other operating system vendor, media
player or music service. Their names and the names of their products are trademarks of their owners and are used here
only to say what the library works with.

- The library reads what media players publish to the operating system on the user's own machine. It does not sign in
  to any service and does not use any service's private interfaces.
- macOS support uses mediaremote-adapter, which relies on a private part of macOS. Apple can change or remove it at
  any time, and the library may stop working there without notice.
- Lyrics and cover art are copyrighted works. The library only reads files the user already has and what players
  publish locally. It ships no lyrics, no cover art and no online provider. If you add a `LyricsProvider`, you are
  responsible for having the right to use what it returns.
- If you use this library with a music service, that service's terms apply to you, including rules about how its
  data and artwork may be shown and whether they may be passed on.

The software is provided "as is", without warranty of any kind. See [LICENSE](LICENSE).
