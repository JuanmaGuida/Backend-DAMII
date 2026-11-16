package com.reclamos.backend.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Shared bounded binary reads used by the format strategies. */
final class BinaryContentReader {
    private BinaryContentReader() { }

    static boolean at(byte[] d, int p, String value) {
        if (p < 0 || value.length() > d.length - p) return false;
        for (int i = 0; i < value.length(); i++) if ((d[p + i] & 255) != value.charAt(i)) return false;
        return true;
    }

    static String text(byte[] d, int p, int n) {
        return new String(d, p, n, StandardCharsets.ISO_8859_1);
    }

    static long u32(byte[] d, int p, boolean littleEndian) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(d, p, 4)
                .order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getInt());
    }

}
