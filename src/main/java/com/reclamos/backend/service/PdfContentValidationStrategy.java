package com.reclamos.backend.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.reclamos.backend.service.BinaryContentReader.at;
import static com.reclamos.backend.service.BinaryContentReader.text;

@Component
public final class PdfContentValidationStrategy implements AttachmentContentValidationStrategy {
    private static final Pattern PDF_END = Pattern.compile("startxref[\\s]+([0-9]{1,10})[\\s]+%%EOF[\\s]*\\z");
    private static final Pattern PDF_SUBSECTION = Pattern.compile(
            "\\s*([0-9]{1,10})[ \\t]+([0-9]{1,10})[ \\t]*(?:\\r\\n|\\r|\\n)");
    private static final Pattern PDF_ENTRY = Pattern.compile(
            "[0-9]{10}[ \\t]+[0-9]{5}[ \\t]+[nf][ \\t]*(?:\\r\\n|\\r|\\n)");

    @Override
    public Set<String> supportedMimeTypes() {
        return Set.of("application/pdf");
    }

    @Override
    public boolean isValid(byte[] d, String declaredMime) {
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

}
