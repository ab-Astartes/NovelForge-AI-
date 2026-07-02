package com.novelforge.core.project;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BookProjectTest {

    @TempDir
    Path tempDir;

    @Test
    void testCreateProject() throws Exception {
        Path bookDir = BookProject.create(tempDir, "测试小说", "xuanhuan", "作者A");

        assertTrue(Files.exists(bookDir.resolve("book.json")));
        assertTrue(Files.exists(bookDir.resolve("outline.md")));
        assertTrue(Files.exists(bookDir.resolve("author_intent.md")));
        assertTrue(Files.exists(bookDir.resolve("truth/characters.json")));
        assertTrue(Files.exists(bookDir.resolve("truth/world.json")));
        assertTrue(Files.exists(bookDir.resolve("truth/timeline.json")));
        assertTrue(Files.exists(bookDir.resolve("truth/hooks.json")));
        assertTrue(Files.exists(bookDir.resolve("config/pipeline.json")));
        assertTrue(Files.exists(bookDir.resolve("chapters")));
    }

    @Test
    void testCreateProjectDuplicate() throws Exception {
        BookProject.create(tempDir, "唯一名", "xuanhuan", "");
        assertThrows(Exception.class, () -> {
            BookProject.create(tempDir, "唯一名", "xuanhuan", "");
        });
    }

    @Test
    void testLoadBook() throws Exception {
        Path bookDir = BookProject.create(tempDir, "加载测试", "urban", "作者B");

        Book book = BookProject.loadBook(bookDir);
        assertEquals("加载测试", book.getTitle());
        assertEquals("urban", book.getGenre());
        assertEquals("作者B", book.getAuthor());
        assertEquals(0, book.getChapters().size());
        assertEquals(1, book.nextChapterNumber());
    }

    @Test
    void testSaveChapter() throws Exception {
        Path bookDir = BookProject.create(tempDir, "章节测试", "xuanhuan", "");

        Chapter chapter = new Chapter();
        chapter.setNumber(1);
        chapter.setFinalText("这是第一章的内容。");

        BookProject.saveChapter(bookDir, chapter);

        Path chapterFile = bookDir.resolve("chapters/chapter-001.md");
        assertTrue(Files.exists(chapterFile));
        assertEquals("这是第一章的内容。", Files.readString(chapterFile));
    }

    @Test
    void testSaveAndLoadMultipleChapters() throws Exception {
        Path bookDir = BookProject.create(tempDir, "多章测试", "xianxia", "");

        Chapter ch1 = new Chapter();
        ch1.setNumber(1);
        ch1.setFinalText("第一章文本");

        Chapter ch2 = new Chapter();
        ch2.setNumber(2);
        ch2.setFinalText("第二章文本");

        BookProject.saveChapter(bookDir, ch1);
        BookProject.saveChapter(bookDir, ch2);

        Book loaded = BookProject.loadBook(bookDir);
        assertEquals(2, loaded.getChapters().size());
    }

    @Test
    void testSanitizeTitle() {
        // BookProject.sanitize is private, but we can verify through create
        // Titles with special chars should become valid directory names
        assertDoesNotThrow(() -> {
            BookProject.create(tempDir, "Book: Test/Name", "fantasy", "");
        });
    }
}
