package com.reclamos.backend.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.zip.CRC32;

import static com.reclamos.backend.service.BinaryContentReader.at;
import static com.reclamos.backend.service.BinaryContentReader.text;
import static com.reclamos.backend.service.BinaryContentReader.u32;

@Component
public final class PngContentValidationStrategy implements AttachmentContentValidationStrategy {
    @Override
    public Set<String> supportedMimeTypes() {
        return Set.of("image/png");
    }

    @Override
    public boolean isValid(byte[] d, String declaredMime) {
        if (d.length < 8 || !at(d, 0, "\u0089PNG\r\n\u001a\n")) return false;
        boolean header = false;
        boolean image = false;
        int compressedBytes = 0;
        int zlibHeader = 0;
        for (int p = 8; p <= d.length - 12;) {
            long length = u32(d, p, false);
            if (length > d.length - p - 12) return false;
            int n = (int) length;
            String type = text(d, p + 4, 4);
            CRC32 crc = new CRC32();
            crc.update(d, p + 4, n + 4);
            if (crc.getValue() != u32(d, p + 8 + n, false)) return false;
            if (!header && !type.equals("IHDR")) return false;
            if (type.equals("IHDR")) {
                if (header || n != 13 || u32(d, p + 8, false) == 0 || u32(d, p + 12, false) == 0)
                    return false;
                int depth = d[p + 16] & 255;
                int color = d[p + 17] & 255;
                boolean depthValid = switch (color) {
                    case 0 -> Set.of(1, 2, 4, 8, 16).contains(depth);
                    case 2, 4, 6 -> depth == 8 || depth == 16;
                    case 3 -> Set.of(1, 2, 4, 8).contains(depth);
                    default -> false;
                };
                if (!depthValid || d[p + 18] != 0 || d[p + 19] != 0
                        || (d[p + 20] != 0 && d[p + 20] != 1)) return false;
                header = true;
            } else if (type.equals("IDAT")) {
                image |= n > 0;
                for (int i = 0; i < n && compressedBytes + i < 2; i++) {
                    zlibHeader = (zlibHeader << 8) | (d[p + 8 + i] & 255);
                }
                compressedBytes += n;
            } else if (type.equals("IEND")) {
                int cmf = zlibHeader >>> 8;
                return n == 0 && image && compressedBytes >= 6 && (cmf & 15) == 8 && cmf <= 120
                        && (zlibHeader & 32) == 0 && zlibHeader % 31 == 0 && p + 12 == d.length;
            }
            p += n + 12;
        }
        return false;
    }

}
