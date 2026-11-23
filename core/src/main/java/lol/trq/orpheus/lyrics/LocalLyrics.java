/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import lol.trq.orpheus.Track;

/**
 * Lyrics from the user's own files: {@code .lrc} files and lyrics embedded in MP3, FLAC and MP4 audio files under
 * the given folders. Nothing leaves the machine.
 *
 * <p>An {@code .lrc} file is matched by its {@code ti}/{@code ar}/{@code length} tags, by the tags of an audio file
 * with the same name next to it, or by a file name of the form {@code Artist - Title.lrc}. Folders are indexed once
 * on a background thread, started by the first lookup; links are not followed. Close it to stop that thread.
 */
public final class LocalLyrics implements LyricsProvider, AutoCloseable {
    private static final int MAX_FILES = 50_000;
    private static final int MAX_DEPTH = 16;
    private static final Pattern TRACK_NUMBER = Pattern.compile("^\\d{1,3}[\\s._-]+");

    private record Entry(Path path, boolean audio, String title, List<String> artists, long durationMillis) {}

    private final List<Path> roots;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private List<Entry> entries = List.of();
    private Thread indexer;
    private boolean started;
    private volatile boolean closed;

    private LocalLyrics(List<Path> roots) {
        this.roots = roots;
    }

    public static LocalLyrics in(Path... folders) {
        return new LocalLyrics(List.of(folders));
    }

    @Override
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public LyricsMatch find(Track track) {
        List<Entry> snapshot;
        synchronized (lock) {
            if (!started && !closed) {
                indexer = new Thread(this::index, "orpheus-index");
                indexer.setDaemon(true);
                indexer.setPriority(Thread.MIN_PRIORITY);
                indexer.start();
            }
            snapshot = entries;
        }
        Entry best = null;
        double bestScore = 0;
        for (Entry entry : snapshot) {
            double score = LyricsMatcher.score(track, entry.title, entry.artists, entry.durationMillis);
            if (score > bestScore || (score == bestScore && best != null && best.audio && !entry.audio)) {
                best = entry;
                bestScore = score;
            }
        }
        if (best == null || bestScore < LyricsMatcher.ACCEPT) {
            return null;
        }
        Lyrics lyrics;
        if (best.audio) {
            lyrics = AudioTags.read(best.path).lyrics();
        } else {
            try {
                lyrics = Files.size(best.path) > Lrc.MAX_BYTES
                        ? Lyrics.NONE
                        : Lrc.read(Files.readAllBytes(best.path)).lyrics();
            } catch (IOException e) {
                return null;
            }
        }
        if (lyrics.kind() == Lyrics.Kind.NONE) {
            return null;
        }
        return new LyricsMatch(lyrics.withMatch(best.path.getFileName().toString(), bestScore), bestScore);
    }

    @Override
    public void close() {
        closed = true;
        Thread thread;
        synchronized (lock) {
            thread = indexer;
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Scans the folders. Runs on the index thread; package-visible so tests can run it inline. */
    void index() {
        synchronized (lock) {
            started = true;
        }
        List<Path> lrcFiles = new ArrayList<>();
        List<Path> audioFiles = new ArrayList<>();
        for (Path root : roots) {
            try {
                Files.walkFileTree(root, java.util.Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (closed || lrcFiles.size() + audioFiles.size() >= MAX_FILES) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (attrs.isRegularFile()) {
                            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (name.endsWith(".lrc")) {
                                lrcFiles.add(file);
                            } else if (name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".m4a")) {
                                audioFiles.add(file);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | RuntimeException e) {
                // an unreadable folder contributes nothing
            }
        }
        Map<String, Integer> byStem = new HashMap<>();
        List<Entry> found = new ArrayList<>();
        for (Path file : lrcFiles) {
            if (closed) {
                return;
            }
            Entry entry = lrcEntry(file);
            if (entry != null) {
                byStem.put(stem(file), found.size());
                found.add(entry);
            }
        }
        publish(found);
        for (Path file : audioFiles) {
            if (closed || Thread.currentThread().isInterrupted()) {
                return;
            }
            AudioTags tags = AudioTags.read(file);
            if (tags.title().isEmpty()) {
                continue;
            }
            List<String> artists = tags.artist().isEmpty() ? List.of() : List.of(tags.artist());
            Integer sidecar = byStem.get(stem(file));
            if (sidecar != null) {
                found.set(
                        sidecar,
                        new Entry(found.get(sidecar).path, false, tags.title(), artists, tags.durationMillis()));
            } else if (tags.lyrics().kind() != Lyrics.Kind.NONE) {
                found.add(new Entry(file, true, tags.title(), artists, tags.durationMillis()));
            }
        }
        publish(found);
    }

    private void publish(List<Entry> found) {
        synchronized (lock) {
            entries = List.copyOf(found);
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    private static Entry lrcEntry(Path file) {
        try {
            if (Files.size(file) > Lrc.MAX_BYTES) {
                return null;
            }
            Lrc lrc = Lrc.read(Files.readAllBytes(file));
            if (lrc.lyrics().kind() == Lyrics.Kind.NONE) {
                return null;
            }
            String name = file.getFileName().toString();
            String base =
                    TRACK_NUMBER.matcher(name.substring(0, name.length() - 4)).replaceFirst("");
            int dash = base.indexOf(" - ");
            String title = !lrc.title().isEmpty() ? lrc.title() : dash > 0 ? base.substring(dash + 3) : base;
            String artist = !lrc.artist().isEmpty() ? lrc.artist() : dash > 0 ? base.substring(0, dash) : "";
            return new Entry(file, false, title, artist.isEmpty() ? List.of() : List.of(artist), lrc.lengthMillis());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String stem(Path file) {
        String path = file.toString();
        int dot = path.lastIndexOf('.');
        return (dot > 0 ? path.substring(0, dot) : path).toLowerCase(Locale.ROOT);
    }
}
