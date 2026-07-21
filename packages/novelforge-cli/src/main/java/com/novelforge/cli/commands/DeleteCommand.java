package com.novelforge.cli.commands;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.project.BookProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * DeleteCommand — delete a project or chapter.
 * Usage:
 *   novelforge delete project <name>   — delete entire project directory
 *   novelforge delete chapter <number> — delete specified chapter from a book
 * Requires confirmation prompt to prevent accidental deletion.
 */
public class DeleteCommand {

    /**
     * Shared Scanner for System.in — never closed to avoid breaking stdin.
     */
    private static final Scanner SHARED_SCANNER = new Scanner(System.in);

    public void execute(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: novelforge delete <project|chapter> <name|number> --book <path>");
            printHelp();
            return;
        }

        String targetType = args[0];
        String targetValue = args[1];
        String bookPath = findOption(args, "--book");

        switch (targetType) {
            case "project" -> deleteProject(targetValue);
            case "chapter" -> deleteChapter(targetValue, bookPath);
            default -> {
                System.err.println("Unknown delete target: " + targetType);
                printHelp();
            }
        }
    }

    private void deleteProject(String projectName) {
        // Search project directory under default books root
        Path booksRoot = Paths.get(System.getProperty("user.home"), "NovelForge", "books");
        Path projectDir = booksRoot.resolve(projectName);

        if (!Files.exists(projectDir)) {
            System.err.println("❌ Project directory not found: " + projectDir);
            return;
        }

        // Confirm deletion
        System.out.println("⚠️  About to delete entire project: " + projectDir);
        System.out.println("   This will remove ALL chapters, states, and configurations.");
        System.out.print("   Type 'yes' to confirm: ");

        // Use shared scanner — never close System.in

        String confirmation = SHARED_SCANNER.nextLine().trim();

        if (!confirmation.equals("yes")) {
            System.out.println("Deletion cancelled.");
            return;
        }

        try {
            deleteDirectory(projectDir);
            System.out.println("✅ Project deleted: " + projectName);
        } catch (Exception e) {
            System.err.println("❌ Failed to delete project: " + e.getMessage());
        }
    }

    private void deleteChapter(String chapterNumStr, String bookPath) {
        if (bookPath == null) {
            System.err.println("Error: --book <path> is required for chapter deletion");
            return;
        }

        int chapterNum;
        try {
            chapterNum = Integer.parseInt(chapterNumStr);
        } catch (NumberFormatException e) {
            System.err.println("Error: chapter number must be an integer");
            return;
        }

        Path bookDir = Paths.get(bookPath);

        try {
            Book book = BookProject.loadBook(bookDir);

            if (chapterNum <= 0 || chapterNum > book.getChapters().size()) {
                System.err.println("❌ Invalid chapter number. Book has " + book.getChapters().size() + " chapters.");
                return;
            }

            Chapter targetChapter = book.getChapters().get(chapterNum - 1);
            System.out.println("⚠️  About to delete chapter " + chapterNum + " (\"" +
                    (targetChapter.getTitle() != null ? targetChapter.getTitle() : "第" + chapterNum + "章") + "\")");
            System.out.println("   Length: " + (targetChapter.getFinalText() != null ?
                    targetChapter.getFinalText().length() : (targetChapter.getDraftText() != null ?
                    targetChapter.getDraftText().length() : 0)) + " chars");
            System.out.print("   Type 'yes' to confirm: ");

            // Use shared scanner — never close System.in

            String confirmation = SHARED_SCANNER.nextLine().trim();

            if (!confirmation.equals("yes")) {
                System.out.println("Deletion cancelled.");
                return;
            }

            // Remove chapter file from disk
            Path chapterFile = bookDir.resolve("chapters")
                    .resolve("chapter-" + String.format("%03d", chapterNum) + ".md");
            if (Files.exists(chapterFile)) {
                Files.delete(chapterFile);
            }

            // Remove draft file if exists
            Path draftFile = bookDir.resolve("chapters")
                    .resolve("chapter-" + String.format("%03d", chapterNum) + ".draft.md");
            if (Files.exists(draftFile)) {
                Files.delete(draftFile);
            }

            // Remove intent file if exists
            Path intentFile = bookDir.resolve("chapters")
                    .resolve("chapter-" + String.format("%03d", chapterNum) + ".intent.md");
            if (Files.exists(intentFile)) {
                Files.delete(intentFile);
            }

            // Remove chapter from Book object
            book.getChapters().remove(chapterNum - 1);

            // Re-number subsequent chapters and update book metadata
            for (int i = 0; i < book.getChapters().size(); i++) {
                book.getChapters().get(i).setNumber(i + 1);
            }

            // Save updated metadata
            BookProject.saveBookMetadata(bookDir, book);

            System.out.println("✅ Chapter " + chapterNum + " deleted. Remaining chapters: " + book.getChapters().size());
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    /** Recursively delete a directory */
    private void deleteDirectory(Path dir) throws Exception {
        if (Files.exists(dir)) {
            for (Path entry : Files.newDirectoryStream(dir)) {
                if (Files.isDirectory(entry)) {
                    deleteDirectory(entry);
                } else {
                    Files.delete(entry);
                }
            }
            Files.delete(dir);
        }
    }

    private void printHelp() {
        System.out.println("""
            Delete command — remove projects or chapters
            
            Usage:
              novelforge delete project <name>              Delete entire project directory
              novelforge delete chapter <number> --book <path>  Delete a specific chapter
            
            WARNING: Deletion is permanent! A confirmation prompt will be shown.
            """);
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
