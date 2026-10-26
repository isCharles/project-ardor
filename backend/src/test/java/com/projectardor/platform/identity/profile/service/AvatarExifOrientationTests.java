package com.projectardor.platform.identity.profile.service;

import static org.assertj.core.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AvatarExifOrientationTests {
    // Expected display corners for EXIF IFD0 tag 0x0112, including mirrored orientations.
    @Test void appliesAllEightOrientationsInBothByteOrdersBeforeStrippingMetadata() throws Exception {
        Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
        int[][] expected = {{0,1,2,3},{1,0,3,2},{3,2,1,0},{2,3,0,1},{0,2,1,3},{2,0,3,1},{3,1,2,0},{1,3,0,2}};
        for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            for (int orientation = 1; orientation <= 8; orientation++) {
                byte[] input = jpeg(orientation, order);
                assertThat(JpegExifOrientation.read(input)).isEqualTo(orientation);
                byte[] output = new AvatarImageNormalizer().normalize(input);
                var image = ImageIO.read(new ByteArrayInputStream(output));
                int[][] samples = {{20,20},{235,20},{20,235},{235,235}};
                for (int corner = 0; corner < 4; corner++) {
                    var actual = new Color(image.getRGB(samples[corner][0], samples[corner][1]));
                    Color wanted = colors[expected[orientation - 1][corner]];
                    assertThat(actual.getRed()).isCloseTo(wanted.getRed(), within(20));
                    assertThat(actual.getGreen()).isCloseTo(wanted.getGreen(), within(20));
                    assertThat(actual.getBlue()).isCloseTo(wanted.getBlue(), within(20));
                }
                assertThat(output).startsWith((byte)137, (byte)80, (byte)78, (byte)71);
                assertThat(JpegExifOrientation.read(output)).isEqualTo(1);
            }
        }
    }
    @Test void malformedMetadataCannotEscapeItsSegmentOrBreakAnOtherwiseValidImage() throws Exception {
        byte[] input = jpeg(6, ByteOrder.LITTLE_ENDIAN);
        java.util.Arrays.fill(input, 16, 20, (byte)255); // An out-of-range IFD0 pointer.
        assertThat(JpegExifOrientation.read(input)).isEqualTo(1);
        assertThat(new AvatarImageNormalizer().normalize(input)).isNotEmpty();
        var random = new java.util.Random(42);
        for (int size = 0; size < 500; size++) {
            byte[] data = new byte[size]; random.nextBytes(data);
            if (size > 1) { data[0] = (byte)255; data[1] = (byte)216; }
            assertThatCode(() -> JpegExifOrientation.read(data)).doesNotThrowAnyException();
        }
    }
    private static byte[] jpeg(int orientation, ByteOrder order) throws Exception {
        var image = new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setColor(Color.RED); g.fillRect(0,0,64,64); g.setColor(Color.GREEN); g.fillRect(64,0,64,64);
            g.setColor(Color.BLUE); g.fillRect(0,64,64,64); g.setColor(Color.YELLOW); g.fillRect(64,64,64,64);
        } finally { g.dispose(); }
        var jpeg = new ByteArrayOutputStream(); ImageIO.write(image,"jpeg",jpeg); image.flush();
        var tiff = ByteBuffer.allocate(26).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? (byte)'I' : (byte)'M');
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? (byte)'I' : (byte)'M');
        tiff.putShort((short)42).putInt(8).putShort((short)1);
        tiff.putShort((short)0x0112).putShort((short)3).putInt(1).putShort((short)orientation).putShort((short)0).putInt(0);
        var result = new ByteArrayOutputStream(); byte[] original = jpeg.toByteArray();
        result.write(original,0,2); result.write(new byte[]{(byte)255,(byte)225,0,34,'E','x','i','f',0,0});
        result.write(tiff.array()); result.write(original,2,original.length-2); return result.toByteArray();
    }
}
