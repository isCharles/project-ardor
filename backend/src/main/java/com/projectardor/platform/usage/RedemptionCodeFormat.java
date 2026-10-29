package com.projectardor.platform.usage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/** Shared code format for plan upgrades and resource packs; only hashes and hints are stored. */
final class RedemptionCodeFormat {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private RedemptionCodeFormat() {
    }

    static String newCode(String prefix) {
        StringBuilder value = new StringBuilder(prefix).append('-');
        for (int group = 0; group < 2; group++) {
            if (group > 0) value.append('-');
            for (int index = 0; index < 4; index++) value.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }

    static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalize(code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成兑换码摘要", exception);
        }
    }

    static String hint(String code) {
        return "••••" + code.substring(code.length() - 4);
    }

    static String trimNote(String value) {
        if (value == null) return null;
        return value.length() <= 200 ? value.strip() : value.strip().substring(0, 200);
    }

    static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
