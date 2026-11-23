/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.cover;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Decoded covers, least recently used first out, bounded by pixel bytes. Thread-safe. */
public final class CoverCache {
    private final long maxBytes;
    private final LinkedHashMap<String, Cover> covers = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;

    public CoverCache(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public synchronized Cover get(String key) {
        return covers.get(key);
    }

    /** Stores a cover. One that is larger than the whole cache is not kept. */
    public synchronized void put(Cover cover) {
        Cover old = covers.remove(cover.key());
        if (old != null) {
            bytes -= old.bytes();
        }
        if (cover.bytes() > maxBytes) {
            return;
        }
        covers.put(cover.key(), cover);
        bytes += cover.bytes();
        Iterator<Map.Entry<String, Cover>> eldest = covers.entrySet().iterator();
        while (bytes > maxBytes && eldest.hasNext()) {
            bytes -= eldest.next().getValue().bytes();
            eldest.remove();
        }
    }

    public synchronized long bytes() {
        return bytes;
    }

    public synchronized int size() {
        return covers.size();
    }
}
