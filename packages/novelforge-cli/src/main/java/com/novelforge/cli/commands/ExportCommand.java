package com.novelforge.cli.commands;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.project.BookProject;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ExportCommand — export book as EPUB, TXT, or MD.
 * Usage: novelforge export --book <path> --format <epub|txt|md> [--output <file>]
 */
public class ExportCommand {

    public void execute(String[] args) {
        String bookPath = findOption(args, "--book");
        String format = findOption(args, "--format");
        String output = findOption(args, "--output");

        if (bookPath == null) {
            System.err.println("Error: --book <path> is required");
            printUsage();
            return;
        }
        if (format == null) {
            format = "txt"; // default
            System.out.println("No format specified, using default: txt");
        }

        Path bookDir = Paths.get(bookPath);

        try {
            Book book = BookProject.loadBook(bookDir);

            if (output == null) {
                output = book.getTitle() + "." + format;
            }

            switch (format.toLowerCase()) {
                case "txt" -> exportTxt(book, output);
                case "md"  -> exportMd(book, output);
                case "epub" -> exportEpub(book, output);
                default -> {
                    System.err.println("Unsupported format: " + format);
                    printUsage();
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Export failed: " + e.getMessage());
        }
    }

    private void exportTxt(Book book, String outputPath) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(book.getTitle()).append("\n");
        sb.append("作者：").append(book.getAuthor()).append("\n");
        sb.append("题材：").append(book.getGenre()).append("\n");
        sb.append("=".repeat(40)).append("\n\n");

        for (Chapter ch : book.getChapters()) {
            String text = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
            if (text == null) continue;
            sb.append("第").append(ch.getNumber()).append("章\n\n");
            sb.append(text);
            sb.append("\n\n").append("-".repeat(40)).append("\n\n");
        }

        Files.writeString(Paths.get(outputPath), sb.toString());
        System.out.println("✅ Exported TXT: " + outputPath);
        System.out.println("   Chapters: " + book.getChapters().size());
    }

    private void exportMd(Book book, String outputPath) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(book.getTitle()).append("\n\n");
        sb.append("**作者**：").append(book.getAuthor()).append("  \n");
        sb.append("**题材**：").append(book.getGenre()).append("  \n\n");
        sb.append("---\n\n");

        for (Chapter ch : book.getChapters()) {
            String text = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
            if (text == null) continue;
            sb.append("## 第").append(ch.getNumber()).append("章\n\n");
            sb.append(text);
            sb.append("\n\n");
        }

        Files.writeString(Paths.get(outputPath), sb.toString());
        System.out.println("✅ Exported Markdown: " + outputPath);
    }

    private void exportEpub(Book book, String outputPath) throws Exception {
        // Simple EPUB 3 generation (valid ZIP with mimetype + content)
        // This is a minimal EPUB builder — no external dependencies needed
        Path out = Paths.get(outputPath);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {

            // 1. mimetype (must be first, uncompressed)
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize("application/epub+zip".getBytes().length);
            mime.setCrc(0x2CAB3750L); // precomputed CRC32 for "application/epub+zip"
            zos.putNextEntry(mime);
            zos.write("application/epub+zip".getBytes());
            zos.closeEntry();

            // 2. META-INF/container.xml
            addStringEntry(zos, "META-INF/container.xml", """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """);

            // 3. OEBPS/content.opf
            StringBuilder opf = new StringBuilder();
            opf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            opf.append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">\n");
            opf.append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");
            opf.append("    <dc:title>").append(escapeXml(book.getTitle())).append("</dc:title>\n");
            opf.append("    <dc:creator>").append(escapeXml(book.getAuthor())).append("</dc:creator>\n");
            opf.append("    <dc:language>zh</dc:language>\n");
            opf.append("  </metadata>\n  <manifest>\n");
            opf.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
            opf.append("    <item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>\n");
            for (int i = 0; i < book.getChapters().size(); i++) {
                opf.append("    <item id=\"ch").append(i + 1).append("\" href=\"ch").append(i + 1).append(".xhtml\" media-type=\"application/xhtml+xml\"/>\n");
            }
            opf.append("  </manifest>\n  <spine toc=\"ncx\">\n");
            for (int i = 0; i < book.getChapters().size(); i++) {
                opf.append("    <itemref idref=\"ch").append(i + 1).append("\"/>\n");
            }
            opf.append("  </spine>\n</package>");
            addStringEntry(zos, "OEBPS/content.opf", opf.toString());

            // 4. Chapter XHTML files
            for (int i = 0; i < book.getChapters().size(); i++) {
                Chapter ch = book.getChapters().get(i);
                String text = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
                String xhtml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE html>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <head><title>第%d章</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
                    <body>
                    <h1>第%d章</h1>
                    %s
                    </body></html>
                    """.formatted(ch.getNumber(), ch.getNumber(), textToHtml(text != null ? text : ""));
                addStringEntry(zos, "OEBPS/ch" + (i + 1) + ".xhtml", xhtml);
            }

            // 5. style.css
            addStringEntry(zos, "OEBPS/style.css", "body { font-family: serif; margin: 2em; } p { text-indent: 2em; line-height: 1.8; }");

            // 6. toc.ncx (simplified)
            StringBuilder ncx = new StringBuilder();
            ncx.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            ncx.append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n  <head><meta name=\"dtb:uid\" content=\"urn:uuid:").append(book.getId() != null ? book.getId() : "unknown").append("\"/></head>\n  <docTitle><text>").append(escapeXml(book.getTitle())).append("</text></docTitle>\n  <navMap>\n");
            for (int i = 0; i < book.getChapters().size(); i++) {
                Chapter ch = book.getChapters().get(i);
                ncx.append("    <navPoint id=\"nav").append(i + 1).append("\"><navLabel><text>第").append(ch.getNumber()).append("章</text></navLabel><content src=\"ch").append(i + 1).append(".xhtml\"/></navPoint>\n");
            }
            ncx.append("  </navMap>\n</ncx>");
            addStringEntry(zos, "OEBPS/toc.ncx", ncx.toString());
        }

        System.out.println("✅ Exported EPUB: " + outputPath);
        System.out.println("   Chapters: " + book.getChapters().size());
    }

    private void addStringEntry(ZipOutputStream zos, String name, String content) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content.getBytes("UTF-8"));
        zos.closeEntry();
    }

    private String textToHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\n", "<br/>");
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void printUsage() {
        System.out.println("""
            Usage: novelforge export --book <path> --format <epub|txt|md> [--output <file>]
            
            Formats:
              txt  — plain text (with chapter separators)
              md   — Markdown (compatible with most editors)
              epub — EPUB 3 (minimal, no external dependencies)
            """);
    }

    private String findOption(String[] args, String key) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return null;
    }
}
