package com.reclamos.backend.service;

import org.springframework.stereotype.Component;

import java.util.Set;


@Component
public final class JpegContentValidationStrategy implements AttachmentContentValidationStrategy {
    @Override
    public Set<String> supportedMimeTypes() {
        return Set.of("image/jpeg");
    }

    @Override
    public boolean isValid(byte[] d, String declaredMime) {
        if (d.length < 4 || (d[0] & 255) != 255 || (d[1] & 255) != 216) return false;
        boolean frame = false;
        boolean scan = false;
        boolean entropy = false;
        int p = 2;
        while (p < d.length) {
            if ((d[p++] & 255) != 255) return false;
            while (p < d.length && (d[p] & 255) == 255) p++;
            if (p == d.length) return false;
            int marker = d[p++] & 255;
            // Some camera JPEGs contain additional data after EOI (e.g. Motion Photos).
            // Identify the JPEG codestream without imposing a new policy on that payload.
            if (marker == 217) return frame && scan && entropy;
            if (marker == 0 || marker == 216) return false;
            if (marker == 1 || (marker >= 208 && marker <= 215)) continue;
            if (p > d.length - 2) return false;
            int n = ((d[p] & 255) << 8) | (d[p + 1] & 255);
            if (n < 2 || n > d.length - p) return false;
            if (marker >= 192 && marker <= 207 && marker != 196 && marker != 200 && marker != 204) {
                if (n < 8 || n != 8 + 3 * (d[p + 7] & 255)
                        || (d[p + 5] == 0 && d[p + 6] == 0)) return false;
                frame = true;
            }
            if (marker == 218) {
                if (!frame || n < 6 || n != 6 + 2 * (d[p + 2] & 255)) return false;
                scan = true;
            }
            p += n;
            if (marker == 218) {
                while (p < d.length) {
                    if ((d[p] & 255) != 255) {
                        entropy = true;
                        p++;
                    } else if (p + 1 < d.length && (d[p + 1] == 0
                            || ((d[p + 1] & 255) >= 208 && (d[p + 1] & 255) <= 215))) {
                        entropy = true;
                        p += 2;
                    } else {
                        break;
                    }
                }
            }
        }
        return false;
    }

}
