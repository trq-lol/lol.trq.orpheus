/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import lol.trq.orpheus.linux.MprisSource;
import lol.trq.orpheus.macos.MacNowPlaying;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.windows.WindowsSessions;

/** What differs between operating systems: the source under test and the publisher that feeds it. */
abstract class Rig {
    static final List<String> WINDOWS_COMMANDS = List.of(
            "pause",
            "play",
            "toggle",
            "next",
            "previous",
            "fast-forward",
            "rewind",
            "seek",
            "shuffle",
            "repeat",
            "rate",
            "stop");

    static final List<String> MACOS_COMMANDS =
            List.of("pause", "play", "toggle", "next", "previous", "seek", "shuffle", "repeat", "stop");

    abstract String name();

    abstract Source source();

    abstract Publisher publisher() throws Exception;

    /** Whether the platform reports a media kind, so video can be told from music. */
    abstract boolean reportsKind();

    static Rig detect(Path publisherPath) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            Path executable = publisherPath != null
                    ? publisherPath
                    : Path.of("native/target/x86_64-pc-windows-msvc/release/orpheus-publisher.exe");
            return new Rig() {
                @Override
                String name() {
                    return "windows";
                }

                @Override
                Source source() {
                    return WindowsSessions.create();
                }

                @Override
                Publisher publisher() throws Exception {
                    if (!Files.isRegularFile(executable)) {
                        throw new IOException(
                                "publisher not found at " + executable.toAbsolutePath() + "; build it with cargo");
                    }
                    return new ProcessPublisher(executable, "orpheus-publisher", WINDOWS_COMMANDS);
                }

                @Override
                boolean reportsKind() {
                    return true;
                }
            };
        }
        if (os.startsWith("linux")) {
            return new Rig() {
                @Override
                String name() {
                    return "linux";
                }

                @Override
                Source source() {
                    return MprisSource.create();
                }

                @Override
                Publisher publisher() throws Exception {
                    return new MprisPublisher();
                }

                @Override
                boolean reportsKind() {
                    return false;
                }
            };
        }
        if (os.startsWith("mac")) {
            Path executable =
                    publisherPath != null ? publisherPath : Path.of("native/publisher-macos/orpheus-publisher");
            return new Rig() {
                @Override
                String name() {
                    return "macos";
                }

                @Override
                Source source() {
                    return MacNowPlaying.create();
                }

                @Override
                Publisher publisher() throws Exception {
                    if (!Files.isRegularFile(executable)) {
                        throw new IOException(
                                "publisher not found at " + executable.toAbsolutePath() + "; build it with build.sh");
                    }
                    return new ProcessPublisher(executable, "publisher", MACOS_COMMANDS);
                }

                @Override
                boolean reportsKind() {
                    return true;
                }
            };
        }
        throw new IOException("no live rig for " + os);
    }
}
