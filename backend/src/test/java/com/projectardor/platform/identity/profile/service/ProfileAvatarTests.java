package com.projectardor.platform.identity.profile.service;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ProfileAvatarTests {
    @Test void normalizesBothFormatsToBoundedSquarePng() throws Exception {
        var normalizer = new AvatarImageNormalizer();
        for (String format : new String[]{"png", "jpeg"}) {
            byte[] result = normalizer.normalize(image(500, 300, format));
            assertThat(result).startsWith((byte)137, (byte)80, (byte)78, (byte)71);
            var decoded = ImageIO.read(new ByteArrayInputStream(result));
            assertThat(decoded.getWidth()).isEqualTo(256);
            assertThat(decoded.getHeight()).isEqualTo(256);
            assertThat(result.length).isLessThan(524288);
        }
    }
    @Test void rejectsInvalidOversizedAndExcessiveDimensionsBeforeStorage() throws Exception {
        var normalizer = new AvatarImageNormalizer();
        for (byte[] input : new byte[][]{new byte[0], "<svg>not a raster</svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[AvatarImageNormalizer.MAX_UPLOAD_BYTES + 1], image(4097, 1, "png")}) {
            assertThatThrownBy(() -> normalizer.normalize(input)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    static byte[] image(int width, int height, String format) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output); image.flush(); return output.toByteArray();
    }
}
