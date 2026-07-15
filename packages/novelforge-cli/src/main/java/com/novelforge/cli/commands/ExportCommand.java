package com.novelforge.cli.commands;

import com.novelforge.core.models.Book;
import com.novelforge.core.project.BookProject;
import com.novelforge.core.export.BookExporter;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ExportCommand — export book as EPUB, TXT, or MD.
 * Delegates to core BookExporter for actual generation logic (🟡-7).
 * Usage: novelforge export --book <path> --format <epub|txt|md> [--output <file>] [--cover <path>]
 */
public class ExportCommand {

    public void execute(String[] args) {
        String bookPath = findOption(args, "--book");
        String format = findOption(args, "--format");
        String output = findOption(args, "--output");
        String coverImagePath = findOption(args, "--cover");

        if (bookPath == null) {
            System.err.println("Error: --book <path> is required");
            printUsage();
            return;
        }
        if (format == null) {
            format = "txt";
            System.out.println("No format specified, using default: txt");
        }

        Path bookDir = Paths.get(bookPath);

        try {
            Book book = BookProject.loadBook(bookDir);
            if (output == null) output = book.getTitle() + "." + format;
            Path outputPath = Paths.get(output);

            switch (format.toLowerCase()) {
                case "txt" -> {
                    BookExporter.exportTxt(book, outputPath);
                    System.out.println("✅ Exported TXT: " + output);
                }
                case "md"  -> {
                    BookExporter.exportMd(book, outputPath);
                    System.out.println("✅ Exported Markdown: " + output);
                }
                case "epub" -> {
                    BookExporter.exportEpub(book, outputPath, coverImagePath);
                    System.out.println("✅ Exported EPUB: " + output);
                }
                default -> {
                    System.err.println("Unsupported format: " + format);
                    printUsage();
                }
            }
            System.out.println("   Chapters: " + book.getChapters().size());
        } catch (Exception e) {
            System.err.println("❌ Export failed: " + e.getMessage());
        }
    }

    private void printUsage() {
        System.out.println("Usage: novelforge export --book <path> --format <epub|txt|md> [--output <file>] [--cover <path>]");
        System.out.println();
        System.out.println("Formats:");
        System.out.println("  txt  — plain text (with chapter separators)");
        System.out.println("  md   — Markdown (compatible with most editors)");
        System.out.println("  epub — EPUB 3 (cover + nav + paragraph formatting)");
        System.out.println("Options:");
        System.out.println("  --cover <path> — cover image for EPUB (jpg/png/gif/svg/webp)");
    }

    private String findOption(String[] args, String key) {
        for (String arg : args) {
            if (arg.startsWith(key + "=")) return arg.substring(key.length() + 1);
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return null;
    }
}
