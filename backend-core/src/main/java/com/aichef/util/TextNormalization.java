package com.aichef.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class TextNormalization {

    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

    private TextNormalization() {
    }

    public static String normalizeRussian(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!looksLikeMojibake(value)) {
            return value;
        }
        String recovered = new String(value.getBytes(WINDOWS_1251), StandardCharsets.UTF_8);
        return recovered.isBlank() ? value : recovered;
    }

    private static boolean looksLikeMojibake(String value) {
        int markers = 0;
        markers += count(value, "Р°");
        markers += count(value, "Рё");
        markers += count(value, "Рѕ");
        markers += count(value, "Рµ");
        markers += count(value, "С‚");
        markers += count(value, "СЊ");
        markers += count(value, "СЏ");
        markers += count(value, "СЂ");
        markers += count(value, "РЅ");
        markers += count(value, "РІ");
        markers += count(value, "Р»");
        markers += count(value, "Рї");
        return markers >= 2;
    }

    private static int count(String value, String token) {
        int from = 0;
        int hits = 0;
        while (true) {
            int idx = value.indexOf(token, from);
            if (idx < 0) {
                return hits;
            }
            hits++;
            from = idx + token.length();
        }
    }
}

