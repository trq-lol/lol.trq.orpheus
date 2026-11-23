/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses the resource directories under {@code src/test/resources/native-fixtures}. */
class NativeFilesTest {
    @TempDir
    Path root;

    private Path extract(String fixture) throws IOException {
        return NativeFiles.extract(NativeFilesTest.class, "native-fixtures/" + fixture, "t", root.resolve("cache"));
    }

    @Test
    void extractsVerifiedFilesIncludingNestedOnes() throws Exception {
        Path directory = extract("good");
        assertEquals("first file\n", Files.readString(directory.resolve("one.bin")));
        assertEquals("nested file\n", Files.readString(directory.resolve("sub/two.bin")));
        assertEquals(directory, extract("good"), "a second call reuses the same directory");
    }

    @Test
    void aTamperedCopyOnDiskIsReplacedBeforeUse() throws Exception {
        Path directory = extract("good");
        Files.writeString(directory.resolve("one.bin"), "planted by someone else");
        Path again = extract("good");
        assertEquals("first file\n", Files.readString(again.resolve("one.bin")));
    }

    @Test
    void badBundlesAreRefusedWithAReason() {
        assertTrue(assertThrows(IOException.class, () -> extract("missing"))
                .getMessage()
                .contains("not bundled"));
        assertTrue(assertThrows(IOException.class, () -> extract("wrong-checksum"))
                .getMessage()
                .contains("checksum"));
        assertTrue(assertThrows(IOException.class, () -> extract("escaping"))
                .getMessage()
                .contains("outside"));
        assertThrows(IOException.class, () -> extract("empty"));
    }

    @Test
    void manyThreadsExtractingAtOnceAllSucceed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Path>> results = new ArrayList<>();
            Callable<Path> task = () -> extract("good");
            for (int i = 0; i < 32; i++) {
                results.add(pool.submit(task));
            }
            for (Future<Path> result : results) {
                assertArrayEquals(
                        "first file\n".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(result.get().resolve("one.bin")));
            }
        } finally {
            pool.shutdownNow();
        }
        try (var children = Files.list(root.resolve("cache"))) {
            assertEquals(1, children.count(), "no staging directories may be left behind");
        }
    }
}
