# H31 attachment fixtures

These are complete 16 x 16 pixel files generated from a solid RGB image (42, 104, 170).
PNG, baseline/progressive JPEG, lossy/lossless/animated WebP and PDF were encoded with
Pillow 13.0.0. HEIC was encoded with pillow-heif 1.8.1 (libheif). The animated WebP
has a second solid frame (1, 2, 3). No personal data or external copyrighted images
are included. The HEVC file is also used for image/heif: HEIC is a HEIF specialization.

All image fixtures were reopened and decoded with Pillow and pillow-heif. The PDF
was normalized, opened and rendered with PDFium (pypdfium2). These encoders/readers were used
only to prepare fixtures and are not build or production dependencies.

`xref-stream.pdf` is a complete single-page PDF with a colored rectangle and a
binary cross-reference stream (W [1 4 2]). `incremental.pdf` appends a catalog
revision and a cross-reference subsection with Prev to the Pillow PDF. Both were
constructed with the standard Python library and opened/rendered with PDFium.

Tests derive truncated files, incorrect lengths, corrupt CRCs, incompatible MIME
claims and executable/text impostors from these fixtures. Production validation
must never be weakened to accommodate synthetic positive test data.
