package com.reclamos.backend.service;

import com.reclamos.backend.exception.InvalidAttachmentException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** Bounded format checks only: no pixel/document decoding, malware scan or active-content analysis. */
final class AttachmentFormatValidator {
    private static final Pattern PDF_END = Pattern.compile("startxref[\\s]+([0-9]{1,10})[\\s]+%%EOF[\\s]*\\z");
    private static final Pattern PDF_SUBSECTION = Pattern.compile(
            "\\s*([0-9]{1,10})[ \\t]+([0-9]{1,10})[ \\t]*(?:\\r\\n|\\r|\\n)");
    private static final Pattern PDF_ENTRY = Pattern.compile(
            "[0-9]{10}[ \\t]+[0-9]{5}[ \\t]+[nf][ \\t]*(?:\\r\\n|\\r|\\n)");
    private static final Set<String> HEVC_BRANDS = Set.of("heic", "heix", "heim", "heis");
    private static final Set<String> HEIF_BOXES = Set.of("hdlr", "pitm", "iloc", "iinf", "iprp");

    private AttachmentFormatValidator() { }

    static void validate(byte[] data, String declaredType) {
        boolean valid = switch (declaredType) {
            case "image/png" -> png(data);
            case "image/jpeg" -> jpeg(data);
            case "image/webp" -> webp(data);
            case "image/heic", "image/heif" -> heif(data, declaredType);
            case "application/pdf" -> pdf(data);
            default -> false;
        };
        if (!valid) {
            throw new InvalidAttachmentException(
                    "El contenido del archivo no corresponde al formato declarado o está incompleto.");
        }
    }

    private static boolean png(byte[] d) {
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

    private static boolean jpeg(byte[] d) {
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

    private static boolean webp(byte[] d) {
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

    private static boolean heif(byte[] d, String mime) {
        if (d.length < 24 || !at(d, 4, "ftyp")) return false;
        Set<String> brands = new HashSet<>();
        boolean metadata = false;
        boolean payload = false;
        int p = 0;
        while (p < d.length) {
            Box box = box(d, p, d.length);
            if (box == null) return false;
            if (at(d, p + 4, "ftyp")) {
                if (p != 0 || box.end - box.body < 8 || (box.end - box.body) % 4 != 0) return false;
                addKnownBrand(brands, text(d, box.body, 4));
                for (int b = box.body + 8; b < box.end; b += 4) addKnownBrand(brands, text(d, b, 4));
            } else if (at(d, p + 4, "meta")) {
                if (box.end - box.body < 4) return false;
                Set<String> children = new HashSet<>();
                int child = box.body + 4;
                while (child < box.end) {
                    Box nested = box(d, child, box.end);
                    if (nested == null) return false;
                    String type = text(d, child + 4, 4);
                    int length = nested.end - nested.body;
                    if (type.equals("hdlr") && (length < 24 || !at(d, nested.body + 8, "pict"))) return false;
                    if (type.equals("pitm") && length < 6) return false;
                    if (type.equals("iloc") && length < 8) return false;
                    if (type.equals("iinf") && length < 14) return false;
                    if (type.equals("iprp") && length < 16) return false;
                    if (HEIF_BOXES.contains(type)) children.add(type);
                    if (at(d, child + 4, "idat")) payload |= nested.end > nested.body;
                    child = nested.end;
                }
                metadata = children.containsAll(HEIF_BOXES);
            } else if (at(d, p + 4, "mdat")) {
                payload |= box.end > box.body;
            }
            p = box.end;
        }
        // AVIF also advertises mif1; it is not an allowed HEIF/HEIC upload.
        if (brands.contains("avif") || brands.contains("avis")) return false;
        boolean hevc = brands.stream().anyMatch(HEVC_BRANDS::contains);
        return metadata && payload && (hevc || (mime.equals("image/heif") && brands.contains("mif1")));
    }

    private static void addKnownBrand(Set<String> brands, String brand) {
        if (HEVC_BRANDS.contains(brand) || brand.equals("mif1") || brand.equals("avif") || brand.equals("avis")) {
            brands.add(brand);
        }
    }

    private static Box box(byte[] d, int p, int end) {
        if (end - p < 8) return null;
        long size = u32(d, p, false);
        int header = 8;
        if (size == 1) {
            if (end - p < 16) return null;
            size = ByteBuffer.wrap(d, p + 8, 8).getLong();
            header = 16;
        } else if (size == 0) {
            size = end - p;
        }
        if (size < header || size > end - p) return null;
        return new Box(p + header, p + (int) size);
    }

    private static boolean pdf(byte[] d) {
        if (d.length < 20 || !at(d, 0, "%PDF-")
                || !((d[5] == '1' && d[6] == '.' && d[7] >= '0' && d[7] <= '7')
                || (d[5] == '2' && d[6] == '.' && d[7] == '0'))
                || (d[8] != '\r' && d[8] != '\n')) return false;
        String document = text(d, 0, d.length);
        Matcher end = PDF_END.matcher(document);
        // Start at the last startxref to avoid scanning repeatedly through compressed streams.
        int last = document.lastIndexOf("startxref");
        if (last < 0 || !end.find(last)) return false;
        long offset = Long.parseLong(end.group(1));
        if (offset < 9 || offset >= last) return false;
        String xref = document.substring((int) offset, last);
        if (xref.startsWith("xref")) {
            return pdfTable(xref)
                    && document.substring(9, (int) offset).contains("endobj");
        }
        return xref.matches("(?s)[0-9]+\\s+[0-9]+\\s+obj\\b.*")
                && Pattern.compile("/Type\\s*/XRef\\b").matcher(xref).find()
                && xref.contains("stream") && xref.contains("endstream") && xref.contains("endobj");
    }

    private static boolean pdfTable(String xref) {
        int p = 4;
        boolean entries = false;
        Matcher subsection = PDF_SUBSECTION.matcher(xref);
        Matcher entry = PDF_ENTRY.matcher(xref);
        while (subsection.region(p, xref.length()).lookingAt()) {
            long count = Long.parseLong(subsection.group(2));
            p = subsection.end();
            // Every entry consumes at least 18 bytes; never loop using an unchecked count.
            if (count == 0 || count > (xref.length() - p) / 18) return false;
            for (long i = 0; i < count; i++) {
                if (!entry.region(p, xref.length()).lookingAt()) return false;
                p = entry.end();
            }
            entries = true;
        }
        String trailer = xref.substring(p).stripLeading();
        return entries && trailer.startsWith("trailer") && trailer.contains("<<")
                && trailer.contains(">>") && trailer.contains("/Root");
    }

    private static boolean at(byte[] d, int p, String value) {
        if (p < 0 || value.length() > d.length - p) return false;
        for (int i = 0; i < value.length(); i++) if ((d[p + i] & 255) != value.charAt(i)) return false;
        return true;
    }

    private static String text(byte[] d, int p, int n) {
        return new String(d, p, n, StandardCharsets.ISO_8859_1);
    }

    private static long u32(byte[] d, int p, boolean littleEndian) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(d, p, 4)
                .order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getInt());
    }

    private static int u16le(byte[] d, int p) {
        return (d[p] & 255) | ((d[p + 1] & 255) << 8);
    }

    private record Box(int body, int end) { }
}
