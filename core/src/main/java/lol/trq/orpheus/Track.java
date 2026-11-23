/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.util.List;
import java.util.Objects;
import lol.trq.orpheus.internal.Text;

/**
 * A piece of media. Every field may be missing: text is empty, numbers are -1 or 0, {@code id} and {@code uri} are
 * null. Text from outside is trimmed and length-capped on construction.
 */
public final class Track {
    public static final Track NONE = new Track(null, "", List.of(), "", "", -1, 0, null, MediaKind.UNKNOWN);

    private static final long DURATION_TOLERANCE_MILLIS = 2000;

    private final String id;
    private final String title;
    private final List<String> artists;
    private final String artistLine;
    private final String album;
    private final String albumArtist;
    private final long durationMillis;
    private final int trackNumber;
    private final String uri;
    private final MediaKind kind;
    private Keys keys;

    /** Comparison forms of the text, computed on first use. */
    private record Keys(String title, List<String> artists, String allArtists) {}

    /**
     * @param id the source's own identifier, or null; never required
     * @param title the title
     * @param artists the performing artists, possibly empty
     * @param album the album title
     * @param albumArtist the album artist
     * @param durationMillis the length, or -1 when unknown
     * @param trackNumber the position on the album, or 0 when unknown
     * @param uri a location the source can play, or null
     * @param kind what the source says this is
     */
    public Track(
            String id,
            String title,
            List<String> artists,
            String album,
            String albumArtist,
            long durationMillis,
            int trackNumber,
            String uri,
            MediaKind kind) {
        this.id = id == null || id.isEmpty() ? null : id;
        this.title = Text.clean(title);
        this.artists = artists == null
                ? List.of()
                : artists.stream().map(Text::clean).filter(a -> !a.isEmpty()).toList();
        this.artistLine = String.join(", ", this.artists);
        this.album = Text.clean(album);
        this.albumArtist = Text.clean(albumArtist);
        this.durationMillis = durationMillis <= 0 ? -1 : durationMillis;
        this.trackNumber = Math.max(trackNumber, 0);
        this.uri = uri == null || uri.isEmpty() ? null : uri;
        this.kind = kind == null ? MediaKind.UNKNOWN : kind;
    }

    public static Track of(String title, String artist) {
        return new Track(null, title, artist == null ? List.of() : List.of(artist), "", "", -1, 0, null, null);
    }

    public Track withDuration(long millis) {
        return new Track(id, title, artists, album, albumArtist, millis, trackNumber, uri, kind);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public List<String> artists() {
        return artists;
    }

    /** The artists joined with ", ". Computed once. */
    public String artistLine() {
        return artistLine;
    }

    public String album() {
        return album;
    }

    public String albumArtist() {
        return albumArtist;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public int trackNumber() {
        return trackNumber;
    }

    public String uri() {
        return uri;
    }

    public MediaKind kind() {
        return kind;
    }

    public boolean isEmpty() {
        return title.isEmpty() && artists.isEmpty();
    }

    /**
     * Whether two descriptions are the same song. Titles must match after clean-up, at least one artist must match
     * when both sides name any, and the lengths must be within two seconds when both are known. Identifiers are not
     * compared: different sources use different ones.
     */
    public boolean sameSong(Track other) {
        if (other == null || isEmpty() || other.isEmpty()) {
            return false;
        }
        if (durationMillis > 0
                && other.durationMillis > 0
                && Math.abs(durationMillis - other.durationMillis) > DURATION_TOLERANCE_MILLIS) {
            return false;
        }
        Keys mine = keys();
        Keys theirs = other.keys();
        if (!mine.title.equals(theirs.title)) {
            return false;
        }
        if (artists.isEmpty() || other.artists.isEmpty() || mine.allArtists.equals(theirs.allArtists)) {
            return true;
        }
        for (String artist : mine.artists) {
            if (theirs.artists.contains(artist)) {
                return true;
            }
        }
        return false;
    }

    /** Racy on purpose: two threads may both compute the same immutable value. */
    private Keys keys() {
        Keys k = keys;
        if (k == null) {
            k = new Keys(Text.title(title), Text.artists(artists), Text.artist(String.join(" ", artists)));
            keys = k;
        }
        return k;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Track t
                && durationMillis == t.durationMillis
                && trackNumber == t.trackNumber
                && kind == t.kind
                && title.equals(t.title)
                && artists.equals(t.artists)
                && album.equals(t.album)
                && albumArtist.equals(t.albumArtist)
                && Objects.equals(id, t.id)
                && Objects.equals(uri, t.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, artists, album, durationMillis);
    }

    @Override
    public String toString() {
        return "Track[" + artistLine + " - " + title + "]";
    }
}
