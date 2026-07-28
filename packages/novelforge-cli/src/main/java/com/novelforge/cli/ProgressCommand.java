package com.novelforge.cli;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.WritingProgress;
import com.novelforge.core.models.WritingProgress.ChapterProgress;
import com.novelforge.core.project.BookProject;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Progress command — show writing progress and statistics.
 * Usage: progress <path>
 */
public class ProgressCommand {

    public void execute(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: progress <项目路径>");
            return;
        }

        String bookPath = args[0];

        try {
            Path path = Paths.get(bookPath);
            Book book = BookProject.loadBook(path);

            System.out.println("《" + book.getTitle() + "》 写作进度");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            int totalChapters = book.getChapters().size();
            System.out.println("总章数: " + totalChapters);

            // Load progress if available
            WritingProgress progress = book.getStoredProgress();
            if (progress != null) {
                System.out.println("总字数: " + progress.getTotalWords());
                System.out.println("平均审阅分: " + String.format("%.1f", progress.getAverageAuditScore()));
                System.out.println("总耗时: " + formatTime(progress.getTotalPipelineTimeMs()));
                System.out.println();

                System.out.println("章节详情:");
                for (ChapterProgress ch : progress.getChapterProgresses()) {
                    String auditStatus = ch.isAudited() ? (ch.isPassed() ? "✅" : "⚠️") : "—";
                    System.out.printf("  第%d章 | %d字 | %s | 分%.1f | %s%n",
                        ch.getChapterNumber(),
                        ch.getWordCount(),
                        auditStatus,
                        ch.getAuditScore(),
                        formatTime(ch.getPipelineTimeMs()));
                }
            } else {
                // Show basic info from book chapters
                System.out.println("(暂无详细进度数据，显示基础章节信息)");
                System.out.println();
                for (int i = 0; i < book.getChapters().size(); i++) {
                    Chapter ch = book.getChapters().get(i);
                    if (ch != null) {
                        System.out.printf("  第%d章 | %d字%n", ch.getNumber(), ch.getWordCount());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("进度查询失败: " + e.getMessage());
        }
    }

    private String formatTime(long ms) {
        long secs = ms / 1000;
        if (secs > 60) {
            return (secs / 60) + "m" + (secs % 60) + "s";
        }
        return secs + "s";
    }
}
