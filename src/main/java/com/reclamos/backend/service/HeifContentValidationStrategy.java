package com.reclamos.backend.service;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import static com.reclamos.backend.service.BinaryContentReader.at;
import static com.reclamos.backend.service.BinaryContentReader.text;
import static com.reclamos.backend.service.BinaryContentReader.u32;

@Component
public final class HeifContentValidationStrategy implements AttachmentContentValidationStrategy {
    private static final Set<String> HEVC_BRANDS = Set.of("heic", "heix", "heim", "heis");
    private static final Set<String> HEIF_BOXES = Set.of("hdlr", "pitm", "iloc", "iinf", "iprp");

    @Override
    public Set<String> supportedMimeTypes() {
        return Set.of("image/heic", "image/heif");
    }

    @Override
    public boolean isValid(byte[] d, String mime) {
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

    private record Box(int body, int end) { }
}
