/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Copies native files out of the jar so the operating system can load them. Files go to a directory only the
 * current user can write, are checked against the SHA-256 list shipped in the jar every time, and are never loaded
 * from a shared temporary folder. Not API.
 *
 * <p>A resource directory holds the files and an {@code index} resource with one {@code <sha256> <relative name>}
 * line per file.
 */
public final class NativeFiles {
    private static final long STALE_MILLIS = 30L * 24 * 60 * 60 * 1000;

    private static final Pattern OWN = Pattern.compile("[0-9a-f]{16}-.*|tmp-.*");

    private record Item(String sha256, String name) {}

    private NativeFiles() {}

    /** The per-user directory extracted files live under. */
    public static Path cacheRoot() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", "");
        Path base;
        if (os.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local == null || local.isBlank()) {
                throw new IOException("LOCALAPPDATA is not set, so there is no safe place to extract native code");
            }
            base = Path.of(local);
        } else if (home.isBlank()) {
            throw new IOException("user.home is not set, so there is no safe place to extract native code");
        } else if (os.startsWith("mac")) {
            base = Path.of(home, "Library", "Caches");
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            base = xdg != null && xdg.startsWith("/") ? Path.of(xdg) : Path.of(home, ".cache");
        }
        if (!base.isAbsolute()) {
            throw new IOException("the cache directory is not an absolute path");
        }
        return base.resolve("orpheus").resolve("native");
    }

    /**
     * Extracts every file of a resource directory and returns the directory they are in.
     *
     * @param anchor a class from the jar that holds the resources
     * @param resourceDirectory the resource path of the directory, without slashes at the ends
     * @param tag kept in the directory name, so copies of the library that must not share files can differ
     * @param root where to extract to, normally {@link #cacheRoot()}
     * @throws IOException with a message fit for showing as the reason a source is unavailable
     */
    public static Path extract(Class<?> anchor, String resourceDirectory, String tag, Path root) throws IOException {
        byte[] index = resource(anchor, resourceDirectory + "/index");
        if (index == null) {
            throw new IOException("the native files are not bundled in this build");
        }
        List<Item> items = new ArrayList<>();
        for (String line : new String(index, StandardCharsets.UTF_8).split("\n")) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length == 2) {
                String name = parts[1].strip();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\") || name.contains(":")) {
                    throw new IOException("the native file list names a path outside its directory");
                }
                items.add(new Item(parts[0].toLowerCase(Locale.ROOT), name));
            }
        }
        if (items.isEmpty()) {
            throw new IOException("the native file list is empty");
        }
        Path target = root.resolve(sha256(index).substring(0, 16) + "-" + tag);
        if (!verified(target, items)) {
            install(anchor, resourceDirectory, items, root, target);
        }
        try {
            Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            // only used to decide what is stale
        }
        sweep(root, target);
        return target;
    }

    private static boolean verified(Path directory, List<Item> items) {
        try {
            for (Item item : items) {
                Path file = directory.resolve(item.name);
                if (!Files.isRegularFile(file)
                        || !sha256(Files.readAllBytes(file)).equals(item.sha256)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void install(Class<?> anchor, String resourceDirectory, List<Item> items, Path root, Path target)
            throws IOException {
        createPrivate(root);
        Path staging = Files.createTempDirectory(root, "tmp-");
        try {
            for (Item item : items) {
                byte[] data = resource(anchor, resourceDirectory + "/" + item.name);
                if (data == null || !sha256(data).equals(item.sha256)) {
                    throw new IOException("a bundled native file does not match its checksum");
                }
                Path file = staging.resolve(item.name);
                Files.createDirectories(file.getParent());
                Files.write(file, data);
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // The target exists: another process got there first, or left a damaged copy behind. Which exception
                // says so differs between systems, so the state on disk decides.
                if (!verified(target, items)) {
                    repair(staging, target, items);
                }
            }
        } finally {
            delete(staging);
        }
        if (!verified(target, items)) {
            throw new IOException("the extracted native files could not be verified");
        }
    }

    /** Replaces damaged files one by one. A file that is in use and intact is left alone. */
    private static void repair(Path staging, Path target, List<Item> items) throws IOException {
        for (Item item : items) {
            Path file = target.resolve(item.name);
            if (Files.isRegularFile(file) && sha256(Files.readAllBytes(file)).equals(item.sha256)) {
                continue;
            }
            Files.createDirectories(file.getParent());
            Files.move(staging.resolve(item.name), file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void createPrivate(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        Files.createDirectories(directory.getParent());
        try {
            Files.createDirectory(
                    directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(directory);
        } catch (FileAlreadyExistsException e) {
            // created by another process in the meantime
        }
    }

    private static void sweep(Path root, Path keep) {
        long cutoff = System.currentTimeMillis() - STALE_MILLIS;
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (!child.equals(keep)
                        && OWN.matcher(child.getFileName().toString()).matches()
                        && Files.getLastModifiedTime(child).toMillis() < cutoff) {
                    delete(child);
                }
            }
        } catch (IOException | RuntimeException e) {
            // stale files are only a little disk space
        }
    }

    private static void delete(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || isReparsePoint(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return attributes.isOther() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    return remove(file);
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException e) {
                    return remove(directory);
                }

                private FileVisitResult remove(Path each) {
                    try {
                        Files.deleteIfExists(each);
                    } catch (IOException e) {
                        // in use by another process: leave it
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            // nothing more to do
        }
    }

    /** Links and Windows junctions are not followed: what they point to is not ours to delete. */
    private static boolean isReparsePoint(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isOther();
        } catch (IOException e) {
            return true;
        }
    }

    private static byte[] resource(Class<?> anchor, String path) throws IOException {
        try (InputStream in = anchor.getResourceAsStream("/" + path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    public static String sha256(byte[] data) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
                hex.append(Character.forDigit(b >> 4 & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
