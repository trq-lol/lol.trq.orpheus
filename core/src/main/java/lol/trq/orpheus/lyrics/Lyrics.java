/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import java.util.List;

/** The lyrics of one track. Immutable; lookups allocate nothing and may run on any thread. */
public final class Lyrics {
    /** How precisely the lyrics are timed. */
    public enum Kind {
        NONE,
        PLAIN,
        LINE,
        WORD
    }

    public static final Lyrics NONE = new Lyrics(Kind.NONE, List.of(), 0, "", 0);

    private final Kind kind;
    private final List<LyricLine> lines;
    private final long[] starts;
    private final long offsetMillis;
    private final String source;
    private final double confidence;

    /**
     * @param kind how the lines are timed
     * @param lines the lines, ordered by start time for synced kinds
     * @param offsetMillis a correction: positive values show every line earlier
     * @param source where the lyrics came from, for display
     * @param confidence how sure the match to the track is, 0 to 1
     */
    public Lyrics(Kind kind, List<LyricLine> lines, long offsetMillis, String source, double confidence) {
        this.kind = kind;
        this.lines = List.copyOf(lines);
        this.offsetMillis = offsetMillis;
        this.source = source;
        this.confidence = confidence;
        this.starts = new long[this.lines.size()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = this.lines.get(i).startMillis();
        }
    }

    public Kind kind() {
        return kind;
    }

    public boolean synced() {
        return kind == Kind.LINE || kind == Kind.WORD;
    }

    public int size() {
        return lines.size();
    }

    public LyricLine line(int index) {
        return lines.get(index);
    }

    public List<LyricLine> lines() {
        return lines;
    }

    public long offsetMillis() {
        return offsetMillis;
    }

    public String source() {
        return source;
    }

    public double confidence() {
        return confidence;
    }

    public Lyrics withOffset(long millis) {
        return new Lyrics(kind, lines, millis, source, confidence);
    }

    public Lyrics withMatch(String newSource, double newConfidence) {
        return new Lyrics(kind, lines, offsetMillis, newSource, newConfidence);
    }

    /**
     * The line being sung at a position.
     *
     * @return the index of the last line that has started, or -1 before the first line and for unsynced lyrics
     */
    public int lineAt(long positionMillis) {
        if (!synced()) {
            return -1;
        }
        long time = positionMillis + offsetMillis;
        int low = 0;
        int high = starts.length - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (starts[mid] <= time) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    /**
     * The word being sung inside a line.
     *
     * @return the index into {@code line(lineIndex).words()}, or -1 before the first word or when the line has none
     */
    public int wordAt(int lineIndex, long positionMillis) {
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            return -1;
        }
        List<LyricWord> words = lines.get(lineIndex).words();
        long time = positionMillis + offsetMillis;
        int found = -1;
        for (int i = 0; i < words.size() && words.get(i).startMillis() <= time; i++) {
            found = i;
        }
        return found;
    }
}
