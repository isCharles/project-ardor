package com.projectardor.platform.identity.profile.service;

import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;

/** Decode only bounded PNG/JPEG images; store a new square PNG without original metadata. */
@Component
public class AvatarImageNormalizer {
    public static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final int SIZE = 256;
    public byte[] normalize(byte[] input) {
        if (input == null || input.length == 0 || input.length > MAX_UPLOAD_BYTES)
            throw new IllegalArgumentException("头像需为不超过 2 MB 的 PNG 或 JPEG 图片");
        ImageReader reader = null;
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            reader = readers.next();
            if (!Set.of("png", "jpeg", "jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw invalid();
            reader.setInput(stream, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION)
                throw new IllegalArgumentException("头像尺寸不能超过 4096 × 4096");
            BufferedImage source = reader.read(0);
            if (source == null) throw invalid();
            var normalized = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            var graphics = normalized.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.transform(orientationTransform(JpegExifOrientation.read(input)));
                int side = Math.min(width, height), x = (width - side) / 2, y = (height - side) / 2;
                graphics.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
            } finally { graphics.dispose(); source.flush(); }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(normalized, "png", output)) throw invalid();
            normalized.flush();
            return output.toByteArray();
        } catch (IOException unreadable) { throw invalid(); }
        finally { if (reader != null) reader.dispose(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("请选择有效的 PNG 或 JPEG 图片"); }
    private static AffineTransform orientationTransform(int orientation) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, SIZE, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, SIZE, SIZE);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, SIZE);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, SIZE, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, SIZE, SIZE);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, SIZE);
            default -> new AffineTransform();
        };
    }
}
