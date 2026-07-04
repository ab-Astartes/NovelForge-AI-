package com.novelforge.core.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Book — top-level container for a novel project.
 * Holds metadata, genre profile, chapters, and truth state references.
 */
public class Book {

    private String id;
    private String title;
    private String author;
    private String genre;        // genre profile key (xuanhuan, xianxia, urban, etc.)
    private WritingStyle style;
    private List<Chapter> chapters = new ArrayList<>();
    private String outline;      // book outline text or JSON
    private String authorIntent; // free-form author intent description

    // --- Getters/Setters ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }
    public WritingStyle getStyle() { return style; }
    public void setStyle(WritingStyle style) { this.style = style; }
    public List<Chapter> getChapters() { return chapters; }
    public String getOutline() { return outline; }
    public void setOutline(String outline) { this.outline = outline; }
    public String getAuthorIntent() { return authorIntent; }
    public void setAuthorIntent(String intent) { this.authorIntent = intent; }

    /** Get next chapter number */
    public int nextChapterNumber() {
        return chapters.size() + 1;
    }

    /** Calculate writing progress statistics */
    public WritingProgress getProgress() {
        WritingProgress progress = new WritingProgress();
        progress.setTotalChapters(chapters.size());
        progress.setTotalWords(0);
        progress.setAuditedChapters(0);
        progress.setPassedChapters(0);

        for (Chapter ch : chapters) {
            String text = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
            int words = estimateWordCount(text);
            progress.setTotalWords(progress.getTotalWords() + words);
            if (ch.getAuditResult() != null) {
                progress.setAuditedChapters(progress.getAuditedChapters() + 1);
                if (ch.getAuditResult().isPass()) {
                    progress.setPassedChapters(progress.getPassedChapters() + 1);
                }
            }
        }

        progress.setAverageWordsPerChapter(chapters.isEmpty() ? 0 :
                progress.getTotalWords() / chapters.size());

        return progress;
    }

    private static int estimateWordCount(String text) {
        if (text == null) return 0;
        int cjk = (int) text.chars().filter(c ->
                (c >= 0x4E00 && c <= 0x9FFF) ||
                (c >= 0x3400 && c <= 0x4DBF) ||
                (c >= 0x20000 && c <= 0x2A6DF)
        ).count();
        return cjk + (text.length() - cjk) / 5;
    }
}
