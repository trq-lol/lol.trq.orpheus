/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.system;

import java.util.Locale;
import lol.trq.orpheus.DisplayRules;
import lol.trq.orpheus.macos.MacNowPlaying;
import lol.trq.orpheus.source.Source;
import lol.trq.orpheus.source.SourceSink;
import lol.trq.orpheus.source.SourceStatus;
import lol.trq.orpheus.windows.WindowsSessions;

/**
 * The media sessions of whatever operating system the program runs on: Windows media sessions, MPRIS on Linux (through
 * the bundled library, or pure Java where it cannot load), Now Playing on macOS (experimental). Its id is always
 * {@code system}, so code that pins or shares by source id is the same everywhere. On any other system it reports itself unavailable.
 */
public final class SystemSessions implements Source {
    private final Source delegate;
    private final String unsupported;

    private SystemSessions(Source delegate, String unsupported) {
        this.delegate = delegate;
        this.unsupported = unsupported;
    }

    public static SystemSessions create() {
        return forSystem(System.getProperty("os.name", ""));
    }

    static SystemSessions forSystem(String name) {
        String os = name.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return new SystemSessions(WindowsSessions.create(), null);
        }
        if (os.startsWith("linux")) {
            return new SystemSessions(new LinuxSessions(), null);
        }
        if (os.startsWith("mac")) {
            return new SystemSessions(MacNowPlaying.create(), null);
        }
        return new SystemSessions(null, "no media session support for " + (name.isBlank() ? "this system" : name));
    }

    @Override
    public String id() {
        return "system";
    }

    @Override
    public boolean foreign() {
        return true;
    }

    @Override
    public DisplayRules display() {
        return delegate == null ? DisplayRules.NONE : delegate.display();
    }

    @Override
    public void start(SourceSink sink) {
        if (delegate == null) {
            sink.status(SourceStatus.unavailable(unsupported));
        } else {
            delegate.start(sink);
        }
    }

    @Override
    public void stop() {
        if (delegate != null) {
            delegate.stop();
        }
    }

    @Override
    public <T> T capability(Class<T> type) {
        return delegate == null ? null : delegate.capability(type);
    }
}
