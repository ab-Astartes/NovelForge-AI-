package com.novelforge.core.export;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BookExporterTest {

    private Book createTestBook() {
        Book book = new Book();
        book.setId("test-book-001");
        book.setTitle("测试小说");
        book.setAuthor("测试作者");
        book.setGenre("武侠");

        Chapter ch1 = new Chapter();
        ch1.setNumber(1);
        ch1.setFinalText("第一章内容\n\n主角踏入学院大门。\n\n教授说道：「欢迎。」");
        book.getChapters().add(ch1);

        Chapter ch2 = new Chapter();
        ch2.setNumber(2);
        ch2.setDraftText("第二章草稿\n\n夜色降临，月光洒落。");
        book.getChapters().add(ch2);

        return book;
    }

    @Test
    void testExportTxt(@TempDir Path tmpDir) throws Exception {
        Book book = createTestBook();
        Path outputPath = tmpDir.resolve("test.txt");

        BookExporter.exportTxt(book, outputPath);

        assertTrue(Files.exists(outputPath));
        String content = Files.readString(outputPath, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("测试小说"));
        assertTrue(content.contains("测试作者"));
        assertTrue(content.contains("第1章"));
        assertTrue(content.contains("第2章"));
        assertTrue(content.contains("主角踏入学院大门"));
    }

    @Test
    void testExportMd(@TempDir Path tmpDir) throws Exception {
        Book book = createTestBook();
        Path outputPath = tmpDir.resolve("test.md");

        BookExporter.exportMd(book, outputPath);

        assertTrue(Files.exists(outputPath));
        String content = Files.readString(outputPath, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("# 测试小说"));
        assertTrue(content.contains("**作者**"));
        assertTrue(content.contains("## 第1章"));
    }

    @Test
    void testExportEpub(@TempDir Path tmpDir) throws Exception {
        Book book = createTestBook();
        Path outputPath = tmpDir.resolve("test.epub");

        BookExporter.exportEpub(book, outputPath, null);

        assertTrue(Files.exists(outputPath));
        assertTrue(outputPath.toFile().length() > 100);

        // Verify it's a valid ZIP (EPUB is a ZIP file)
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(outputPath.toFile())) {
            // mimetype must be first entry and STORED (no compression)
            java.util.zip.ZipEntry mimetype = zip.getEntry("mimetype");
            assertNotNull(mimetype, "EPUB must have mimetype entry");
            assertEquals(java.util.zip.ZipEntry.STORED, mimetype.getMethod(),
                "mimetype must be STORED (uncompressed)");

            String mimeContent = new java.io.BufferedReader(
                new java.io.InputStreamReader(zip.getInputStream(mimetype))).readLine();
            assertEquals("application/epub+zip", mimeContent);

            // Must have container.xml
            assertNotNull(zip.getEntry("META-INF/container.xml"));

            // Must have content.opf
            assertNotNull(zip.getEntry("OEBPS/content.opf"));

            // Must have chapter files
            assertNotNull(zip.getEntry("OEBPS/ch1.xhtml"));
            assertNotNull(zip.getEntry("OEBPS/ch2.xhtml"));

            // Must have nav.xhtml
            assertNotNull(zip.getEntry("OEBPS/nav.xhtml"));

            // Must have style.css
            assertNotNull(zip.getEntry("OEBPS/style.css"));
        }
    }

    @Test
    void testExportEpubWithCover(@TempDir Path tmpDir) throws Exception {
        Book book = createTestBook();
        Path outputPath = tmpDir.resolve("test-cover.epub");

        // Create a minimal PNG cover file (1x1 pixel, 67 bytes)
        Path coverPath = tmpDir.resolve("cover.png");
        byte[] minimalPng = createMinimalPng();
        Files.write(coverPath, minimalPng);

        BookExporter.exportEpub(book, outputPath, coverPath.toString());

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(outputPath.toFile())) {
            assertNotNull(zip.getEntry("OEBPS/cover.xhtml"));
            assertNotNull(zip.getEntry("OEBPS/cover.png"));
            assertNotNull(zip.getEntry("OEBPS/content.opf"));
        }
    }

    @Test
    void testExportTxtFallbackToDraftText(@TempDir Path tmpDir) throws Exception {
        Book book = new Book();
        book.setTitle("仅草稿");
        book.setAuthor("作者");
        book.setGenre("都市");

        Chapter ch = new Chapter();
        ch.setNumber(1);
        ch.setDraftText("草稿内容");
        ch.setFinalText(null); // no final text, should use draft
        book.getChapters().add(ch);

        Path outputPath = tmpDir.resolve("draft-only.txt");
        BookExporter.exportTxt(book, outputPath);

        String content = Files.readString(outputPath, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("草稿内容"));
    }

    @Test
    void testTextToHtml() {
        String text = "第一段\n\n第二段\n\n第三段";
        String html = BookExporter.textToHtml(text);
        assertTrue(html.contains("<p>"));
        assertTrue(html.contains("</p>"));
        assertEquals(3, html.split("<p>").length - 1);
    }

    @Test
    void testTextToHtmlEscapesXml() {
        String text = "他 <想到> 了 \"计划\" & 执行";
        String html = BookExporter.textToHtml(text);
        assertTrue(html.contains("&lt;"));
        assertTrue(html.contains("&gt;"));
        assertTrue(html.contains("&amp;"));
        assertFalse(html.contains("<想到>"));  // should be escaped
    }

    @Test
    void testTextToHtmlNull() {
        assertEquals("", BookExporter.textToHtml(null));
    }

    @Test
    void testEscapeXml() {
        assertEquals("&amp;", BookExporter.escapeXml("&"));
        assertEquals("&lt;", BookExporter.escapeXml("<"));
        assertEquals("&gt;", BookExporter.escapeXml(">"));
        assertEquals("", BookExporter.escapeXml(null));
    }

    @Test
    void testGuessMediaType() {
        assertEquals("image/jpeg", BookExporter.guessMediaType("cover.jpg"));
        assertEquals("image/jpeg", BookExporter.guessMediaType("photo.jpeg"));
        assertEquals("image/png", BookExporter.guessMediaType("icon.png"));
        assertEquals("image/gif", BookExporter.guessMediaType("anim.gif"));
        assertEquals("image/svg+xml", BookExporter.guessMediaType("diagram.svg"));
        assertEquals("image/webp", BookExporter.guessMediaType("photo.webp"));
        assertEquals("image/png", BookExporter.guessMediaType("unknown.xyz")); // fallback
    }

    /** Create a minimal valid PNG (1x1 pixel, RGBA) */
    private byte[] createMinimalPng() {
        // Minimal PNG: signature + IHDR + IDAT + IEND
        byte[] png = new byte[67];
        // PNG signature
        png[0] = (byte) 137; png[1] = (byte) 80; png[2] = (byte) 78;
        png[3] = (byte) 71; png[4] = (byte) 13; png[5] = (byte) 10;
        png[6] = (byte) 26; png[7] = (byte) 10;
        // The rest is filled with 0 — not a valid PNG but sufficient for export testing
        // (just checking the file gets included in EPUB, not rendered)
        return png;
    }
}
