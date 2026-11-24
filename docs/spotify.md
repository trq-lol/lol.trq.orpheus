# Spotify

`orpheus-spotify` reads and controls what a Spotify account plays, on any device, through the Spotify Web API.
Experimental and not yet tested against a real account. It needs an access token from you; the library never signs in.

```java
TokenSource tokens = () -> session.accessToken(); // null while signed out

Orpheus orpheus = Orpheus.builder()
        .source(SpotifySource.create(tokens)) // first: preferred
        .source(SystemSessions.create())      // used while Spotify shows nothing
        .build();
```

## Token

| Scope | For |
|---|---|
| `user-read-playback-state` | Track, position, devices, queue |
| `user-modify-playback-state` | Controls, queue, transfer. Needs Premium. |
| `playlist-read-private` | Playlists |
| `user-library-modify` | Like |

- Return a new token when the old one is about to expire. `rejected(token)` is called when Spotify refuses it.
- After sign-in, call `refresh()` on the source.
- Override `HttpCall` to use your own HTTP client.

## Requests

| State | Interval |
|---|---|
| Playing | 5 s, and at the end of the track |
| Paused | 10 s |
| Nothing active | 15 s |
| Signed out | No requests |
| Rate limited | None until Spotify allows it |

A change in the Spotify session of another source, such as the system, is read within about 2 s.

## Not offered

Reordering or removing queue entries, playback rate and stop. Ads have no track data.

## Rules

Spotify requires its name, a link to the track and unaltered artwork: see `DisplayRules`. Its developer terms also
limit commercial use, lyric and video synchronization, and apps in development mode to five users. Read the
[developer terms](https://developer.spotify.com/terms) before you ship.

## Other services

A source needs an official API for the current playback. None was found for TIDAL, SoundCloud or Apple Music, so use
the system session.
