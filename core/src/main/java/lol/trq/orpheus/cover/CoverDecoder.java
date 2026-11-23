/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.cover;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Turns encoded image bytes into a {@link Cover}. Input is untrusted: anything odd gives null, never an exception. */
public final class CoverDecoder {
    public static final int MAX_ENCODED_BYTES = 16 << 20;
    public static final int MAX_SOURCE_SIDE = 4096;

    private CoverDecoder() {}

    /**
     * Decodes and scales an image so that its longer side is at most {@code maxSide}.
     *
     * @return the cover, or null when the data is not a readable image, is too large, or the runtime has no image
     *     support
     */
    public static Cover decode(String key, byte[] data, int maxSide) {
        if (data == null || data.length == 0 || data.length > MAX_ENCODED_BYTES || maxSide < 1) {
            return null;
        }
        try {
            BufferedImage image = read(data, maxSide);
            return image == null ? null : toCover(key, fit(image, maxSide));
        } catch (Exception | LinkageError | StackOverflowError | OutOfMemoryError e) {
            return null;
        }
    }

    private static BufferedImage read(byte[] data, int maxSide) throws Exception {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            String format = format(data);
            if (format == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE) {
                    return null;
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / maxSide);
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** The format the data starts like, or null. Other formats a host has readers for are not decoded. */
    public static String format(byte[] d) {
        if (d.length < 8) {
            return null;
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "gif";
        }
        return d[0] == 'B' && d[1] == 'M' ? "bmp" : null;
    }

    private static BufferedImage fit(BufferedImage image, int maxSide) {
        int width = image.getWidth();
        int height = image.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxSide && image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        double scale = Math.min(1.0, (double) maxSide / longest);
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static Cover toCover(String key, BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] rgba = new byte[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            rgba[i * 4] = (byte) (p >> 16);
            rgba[i * 4 + 1] = (byte) (p >> 8);
            rgba[i * 4 + 2] = (byte) p;
            rgba[i * 4 + 3] = (byte) (p >>> 24);
        }
        int[] sample = Palette.sample(rgba, width, height);
        return new Cover(key, width, height, rgba, Palette.average(sample), Palette.dominant(sample, 5));
    }
}
