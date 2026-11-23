/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.cover;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Colour statistics of an image: the average, and the dominant colours found by splitting the widest colour range. */
public final class Palette {
    private static final int GRID = 32;

    private Palette() {}

    /** Picks up to 32x32 evenly spaced, mostly opaque pixels as 0xRRGGBB. */
    public static int[] sample(byte[] rgba, int width, int height) {
        int cols = Math.min(GRID, width);
        int rows = Math.min(GRID, height);
        int[] out = new int[cols * rows];
        int n = 0;
        for (int row = 0; row < rows; row++) {
            int y = (int) ((row + 0.5) * height / rows);
            for (int col = 0; col < cols; col++) {
                int x = (int) ((col + 0.5) * width / cols);
                int i = (y * width + x) * 4;
                if ((rgba[i + 3] & 0xFF) >= 128) {
                    out[n++] = (rgba[i] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8 | (rgba[i + 2] & 0xFF);
                }
            }
        }
        return Arrays.copyOf(out, n);
    }

    public static int average(int[] colours) {
        if (colours.length == 0) {
            return 0;
        }
        long r = 0;
        long g = 0;
        long b = 0;
        for (int c : colours) {
            r += c >> 16 & 0xFF;
            g += c >> 8 & 0xFF;
            b += c & 0xFF;
        }
        int n = colours.length;
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    /** Up to {@code count} colours, the one covering the most samples first. */
    public static int[] dominant(int[] colours, int count) {
        if (colours.length == 0) {
            return new int[0];
        }
        List<int[]> boxes = new ArrayList<>();
        boxes.add(colours.clone());
        while (boxes.size() < count) {
            int widest = -1;
            int widestRange = 0;
            int widestChannel = 0;
            for (int i = 0; i < boxes.size(); i++) {
                for (int shift = 0; shift <= 16; shift += 8) {
                    int range = range(boxes.get(i), shift);
                    if (range > widestRange) {
                        widestRange = range;
                        widest = i;
                        widestChannel = shift;
                    }
                }
            }
            if (widest < 0) {
                break;
            }
            int[] box = boxes.remove(widest);
            int shift = widestChannel;
            int low = 255;
            int high = 0;
            for (int c : box) {
                low = Math.min(low, c >> shift & 0xFF);
                high = Math.max(high, c >> shift & 0xFF);
            }
            int threshold = (low + high) / 2;
            boxes.add(Arrays.stream(box)
                    .filter(c -> (c >> shift & 0xFF) <= threshold)
                    .toArray());
            boxes.add(Arrays.stream(box)
                    .filter(c -> (c >> shift & 0xFF) > threshold)
                    .toArray());
        }
        boxes.removeIf(box -> box.length == 0);
        boxes.sort((x, y) -> Integer.compare(y.length, x.length));
        int[] out = new int[boxes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = average(boxes.get(i));
        }
        return out;
    }

    private static int range(int[] box, int shift) {
        int min = 255;
        int max = 0;
        for (int c : box) {
            int v = c >> shift & 0xFF;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return box.length < 2 ? 0 : max - min;
    }
}
