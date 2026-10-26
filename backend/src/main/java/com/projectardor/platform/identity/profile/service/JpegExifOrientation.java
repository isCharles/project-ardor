package com.projectardor.platform.identity.profile.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads only bounded JPEG APP1 / TIFF IFD0 orientation; never follows other metadata pointers. */
final class JpegExifOrientation {
    private JpegExifOrientation() {}
    static int read(byte[] bytes) {
        if (bytes.length < 4 || u8(bytes, 0) != 255 || u8(bytes, 1) != 216) return 1;
        int position = 2;
        while (position + 4 <= bytes.length) {
            if (u8(bytes, position++) != 255) return 1;
            while (position < bytes.length && u8(bytes, position) == 255) position++;
            if (position >= bytes.length) return 1;
            int marker = u8(bytes, position++);
            if (marker == 218 || marker == 217) return 1;
            if (marker == 216 || marker == 1 || (marker >= 208 && marker <= 215)) continue;
            if (position + 2 > bytes.length) return 1;
            int size = (u8(bytes, position) << 8) | u8(bytes, position + 1);
            if (size < 2 || position + size > bytes.length) return 1;
            int payload = position + 2, end = position + size;
            if (marker == 225 && payload + 6 <= end && bytes[payload] == 'E' && bytes[payload + 1] == 'x'
                    && bytes[payload + 2] == 'i' && bytes[payload + 3] == 'f' && bytes[payload + 4] == 0 && bytes[payload + 5] == 0)
                return tiff(bytes, payload + 6, end);
            position = end;
        }
        return 1;
    }
    private static int tiff(byte[] bytes, int start, int end) {
        if (end - start < 8) return 1;
        boolean little = bytes[start] == 'I' && bytes[start + 1] == 'I';
        if (!little && !(bytes[start] == 'M' && bytes[start + 1] == 'M')) return 1;
        var data = ByteBuffer.wrap(bytes, start, end - start).slice().order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        if (Short.toUnsignedInt(data.getShort(2)) != 42) return 1;
        long first = Integer.toUnsignedLong(data.getInt(4));
        if (first < 8 || first + 2 > data.limit()) return 1;
        int count = Short.toUnsignedInt(data.getShort((int) first));
        if (first + 2L + count * 12L > data.limit()) return 1;
        for (int index = 0; index < count; index++) {
            int entry = (int) first + 2 + index * 12;
            if (Short.toUnsignedInt(data.getShort(entry)) != 0x0112 || Short.toUnsignedInt(data.getShort(entry + 2)) != 3
                    || data.getInt(entry + 4) != 1) continue;
            int orientation = Short.toUnsignedInt(data.getShort(entry + 8));
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
        }
        return 1;
    }
    private static int u8(byte[] bytes, int index) { return Byte.toUnsignedInt(bytes[index]); }
}
