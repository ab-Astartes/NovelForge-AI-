package com.novelforge.cli.commands;

import com.novelforge.core.project.BookProject;
import com.novelforge.core.models.Book;
import com.novelforge.core.state.TruthState;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.pipeline.PipelineRunner;
import com.novelforge.core.llm.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * BookCommand — create and manage book projects.
 * Subcommands: create, list, info, delete
 */
public class BookCommand {

    private static final ObjectMapper mapper = new ObjectMapper();

    public void execute(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: novelforge book <create|list|info> [options]");
            return;
        }
        switch (args[0]) {
            case "create" -> createBook(args);
            case "list"   -> listBooks(args);
            case "info"   -> infoBook(args);
            default       -> System.err.println("Unknown subcommand: book " + args[0]);
        }
    }

    private void createBook(String[] args) {
        String title = findOption(args, "--title");
        String genre = findOption(args, "--genre");
        String author = findOption(args, "--author");
        String path = findOption(args, "--path");

        if (title == null) {
            System.err.println("Error: --title is required");
            System.err.println("Usage: novelforge book create --title <name> --genre <key> [--author <name>] [--path <dir>]");
            return;
        }
        if (genre == null) {
            genre = "xuanhuan"; // default
            System.out.println("No genre specified, using default: xuanhuan");
        }

        Path parentDir = path != null ? Paths.get(path) : Paths.get(System.getProperty("user.home"), "NovelForge", "books");

        try {
            Path bookDir = BookProject.create(parentDir, title, genre, author);
            System.out.println("✅ Book project created: " + title);
            System.out.println("   Path: " + bookDir);
            System.out.println("   Genre: " + genre);
            System.out.println("   Edit author_intent.md to define your story direction, then run:");
            System.out.println("   novelforge write next --book " + bookDir);
        } catch (Exception e) {
            System.err.println("❌ Failed to create book: " + e.getMessage());
        }
    }

    private void listBooks(String[] args) {
        String path = findOption(args, "--path");
        Path booksDir = path != null ? Paths.get(path) : Paths.get(System.getProperty("user.home"), "NovelForge", "books");

        if (!Files.exists(booksDir)) {
            System.out.println("No books directory found. Create one with: novelforge book create --title <name>");
            return;
        }

        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(booksDir)) {
            boolean found = false;
            for (Path p : ds) {
                if (Files.exists(p.resolve("book.json"))) {
                    try (java.io.InputStream is = Files.newInputStream(p.resolve("book.json"))) {
                        JsonNode bookJson = mapper.readTree(is);
                        String title = bookJson.get("title").asText();
                        String genre = bookJson.get("genre").asText();
                        int chapters = bookJson.has("chapters") ? bookJson.get("chapters").size() : 0;
                        System.out.printf("  %-20s  genre=%-8s  chapters=%d  path=%s%n", title, genre, chapters, p);
                        found = true;
                    }
                }
            }
            if (!found) System.out.println("No book projects found.");
        } catch (Exception e) {
            System.err.println("Failed to list books: " + e.getMessage());
        }
    }

    private void infoBook(String[] args) {
        String bookPath = findOption(args, "--book");
        if (bookPath == null) {
            System.err.println("Error: --book <path> is required");
            return;
        }

        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            System.out.println("Title: " + book.getTitle());
            System.out.println("Genre: " + book.getGenre());
            System.out.println("Author: " + book.getAuthor());
            System.out.println("Chapters: " + book.getChapters().size());
            System.out.println("Next chapter: " + book.nextChapterNumber());
        } catch (Exception e) {
            System.err.println("Failed to load book: " + e.getMessage());
        }
    }

    private String findOption(String[] args, String key) {
        // Support --key=value format
        for (String arg : args) {
            if (arg.startsWith(key + "=")) {
                return arg.substring(key.length() + 1);
            }
        }
        // Support --key value format
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return null;
    }
}
