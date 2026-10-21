package com.reclamos.backend.service;

import org.springframework.stereotype.Component;

import java.util.Set;

import static com.reclamos.backend.service.BinaryContentReader.at;
import static com.reclamos.backend.service.BinaryContentReader.text;
import static com.reclamos.backend.service.BinaryContentReader.u32;

@Component
public final class WebpContentValidationStrategy implements AttachmentContentValidationStrategy {
    @Override
    public Set<String> supportedMimeTypes() {
        return Set.of("image/webp");
    }

    @Override
    public boolean isValid(byte[] d, String declaredMime) {
        return d.length >= 20 && at(d, 0, "RIFF") && at(d, 8, "WEBP")
                && u32(d, 4, true) == d.length - 8 && webpChunks(d, 12, d.length, false);
    }

    private static boolean webpChunks(byte[] d, int start, int end, boolean frame) {
        boolean image = false;
        int p = start;
        while (p <= end - 8) {
            long length = u32(d, p + 4, true);
            if (length > end - p - 8) return false;
            int n = (int) length;
            int body = p + 8;
            String type = text(d, p, 4);
            if (type.equals("VP8 ")) {
                if (n <= 10 || (d[body] & 1) != 0 || !at(d, body + 3, "\u009d\u0001\u002a")
                        || (u16le(d, body + 6) & 16383) == 0 || (u16le(d, body + 8) & 16383) == 0)
                    return false;
                image = true;
            } else if (type.equals("VP8L")) {
                if (n <= 5 || (d[body] & 255) != 47 || (d[body + 4] & 224) != 0) return false;
                image = true;
            } else if (type.equals("VP8X")) {
                if (frame || n != 10) return false;
            } else if (type.equals("ANMF")) {
                if (frame || n < 24 || !webpChunks(d, body + 16, body + n, true)) return false;
                image = true;
            }
            p = body + n + (n & 1);
        }
        return image && p == end;
    }

    private static int u16le(byte[] d, int p) {
        return (d[p] & 255) | ((d[p + 1] & 255) << 8);
    }

}
