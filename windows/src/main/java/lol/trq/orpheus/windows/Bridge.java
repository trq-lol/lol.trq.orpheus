/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.windows;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Locale;
import lol.trq.orpheus.internal.NativeFiles;

/**
 * The only door to the native library. It has a single native method whose name and class are told to the library at
 * load time, so renaming or relocating this class (shading, obfuscation) does not break the link.
 */
final class Bridge {
    static final int ABI = 1;
    static final int OP_ABI = 0;
    static final int OP_OPEN = 1;
    static final int OP_AWAIT = 2;
    static final int OP_COMMAND = 3;
    static final int OP_CLOSE = 4;

    private static final String HANDSHAKE_PROPERTY = "orpheus-native-bind";
    private static final String RESOURCES = "orpheus-native/" + ABI + "/windows-x64";
    private static final String FILE = "orpheus.dll";

    private static boolean loaded;

    private Bridge() {}

    private static native byte[] call(int op, long handle, byte[] argument);

    static byte[] invoke(int op, long handle, byte[] argument) {
        return call(op, handle, argument);
    }

    /**
     * Loads the native library once per class.
     *
     * @return null when it is ready, otherwise the reason it cannot be used
     */
    static synchronized String load() {
        if (loaded) {
            return null;
        }
        // a failure is not remembered: a full disk or a locked file may be gone by the next start
        String failure = tryLoad();
        loaded = failure == null;
        return failure;
    }

    private static String tryLoad() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows")) {
            return "not running on Windows";
        }
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            return "no native library for the " + arch + " architecture";
        }
        String method = null;
        for (Method candidate : Bridge.class.getDeclaredMethods()) {
            if (Modifier.isNative(candidate.getModifiers())) {
                method = candidate.getName();
            }
        }
        if (method == null) {
            return "the native method is missing from this build";
        }
        try {
            String tag = Integer.toHexString(Bridge.class.getName().hashCode());
            Path root = NativeFiles.cacheRoot();
            try {
                bind(NativeFiles.extract(Bridge.class, RESOURCES, tag, root).resolve(FILE), method);
            } catch (UnsatisfiedLinkError e) {
                if (!String.valueOf(e.getMessage()).contains("already loaded")) {
                    throw e;
                }
                // the same file is loaded by another class loader of this process: use a private copy
                String unique = tag + "-" + Integer.toHexString(System.identityHashCode(Bridge.class.getClassLoader()));
                bind(NativeFiles.extract(Bridge.class, RESOURCES, unique, root).resolve(FILE), method);
            }
            byte[] abi = call(OP_ABI, 0, null);
            int found = abi == null || abi.length != 4
                    ? -1
                    : ByteBuffer.wrap(abi).order(ByteOrder.LITTLE_ENDIAN).getInt();
            return found == ABI ? null : "the native library has version " + found + " but this code needs " + ABI;
        } catch (IOException e) {
            return e.getMessage();
        } catch (LinkageError | RuntimeException e) {
            return "the native library could not be loaded: " + e.getMessage();
        }
    }

    private static void bind(Path library, String method) {
        synchronized (HANDSHAKE_PROPERTY.intern()) {
            System.setProperty(HANDSHAKE_PROPERTY, Bridge.class.getName() + "\n" + method);
            try {
                System.load(library.toAbsolutePath().toString());
            } finally {
                System.clearProperty(HANDSHAKE_PROPERTY);
            }
        }
    }
}
