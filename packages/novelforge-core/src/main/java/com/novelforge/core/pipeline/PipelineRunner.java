package com.novelforge.core.pipeline;

import com.novelforge.core.agent.AgentPipeline;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.WritingProgress;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.state.TruthState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * PipelineRunner — executes a full or partial writing pipeline for a book.
 * Handles setup, execution, state persistence, error recovery, and progress tracking.
 */
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final AgentPipeline pipeline;
    private final PipelineConfig config;
    private ProgressListener externalListener;

    /** Internal timing collector — captures per-agent timing, forwards to external listener */
    private final List<WritingProgress.AgentTiming> currentTimings = new ArrayList<>();
    private long currentPipelineStartMs;

    public PipelineRunner(PipelineConfig config, ModelRouter router) {
        this.config = config;
        this.pipeline = new AgentPipeline(router);
    }

    /** Set progress listener on the underlying pipeline (via forwarding wrapper) */
    public void setProgressListener(ProgressListener listener) {
        this.externalListener = listener;
        // We install a forwarding wrapper that also collects timing data
        pipeline.setProgressListener(new ProgressListener() {
            @Override public void onAgentStart(String name, int step, int total) {
                if (externalListener != null) externalListener.onAgentStart(name, step, total);
            }
            @Override public void onAgentComplete(String name, int step, int total, long elapsedMs, String summary) {
                currentTimings.add(new WritingProgress.AgentTiming(name, elapsedMs, 0));
                if (externalListener != null) externalListener.onAgentComplete(name, step, total, elapsedMs, summary);
            }
            @Override public void onAgentSkip(String name, int step, int total) {
                if (externalListener != null) externalListener.onAgentSkip(name, step, total);
            }
            @Override public void onAgentFail(String name, int step, int total, String error) {
                if (externalListener != null) externalListener.onAgentFail(name, step, total, error);
            }
            @Override public void onPipelineComplete(int chapters, int words, double score) {
                if (externalListener != null) externalListener.onPipelineComplete(chapters, words, score);
            }
            @Override public void onPipelineFail(String error) {
                if (externalListener != null) externalListener.onPipelineFail(error);
            }
        });
    }

    /** Reset timing collection for a new pipeline run */
    private void resetTimings() {
        currentTimings.clear();
        currentPipelineStartMs = System.currentTimeMillis();
    }

    /** Update book progress with per-chapter stats after a successful pipeline run */
    private void updateBookProgress(Book book, Chapter chapter, PipelineContext finalContext) {
        WritingProgress progress = book.getProgress();
        if (progress == null) {
            progress = new WritingProgress();
            book.setProgress(progress);
        }

        WritingProgress.ChapterProgress cp = new WritingProgress.ChapterProgress();
        cp.setChapterNumber(chapter.getNumber());
        cp.setChapterTitle(chapter.getTitle() != null ? chapter.getTitle() : "Chapter " + chapter.getNumber());
        cp.setWordCount(chapter.getFinalText() != null ? chapter.getFinalText().length() : 0);
        cp.setPipelineTimeMs(System.currentTimeMillis() - currentPipelineStartMs);
        cp.getAgentTimings().addAll(new ArrayList<>(currentTimings));

        // Audit info
        if (finalContext != null && finalContext.getAuditResult() != null) {
            cp.setAudited(true);
            cp.setAuditScore(finalContext.getAuditResult().getOverallScore());
            cp.setPassed(finalContext.getAuditResult().getOverallScore() >= config.getAuditPassThreshold());
        }

        progress.getChapterProgresses().add(cp);
        progress.computeFromChapters();
        log.info("Progress updated: {} chapters, {} words, avg score {:.1f}",
                progress.getTotalChapters(), progress.getTotalWords(), progress.getAverageAuditScore());
    }

    /** Run the "write next chapter" pipeline */
    public PipelineResult writeNextChapter(Book book, TruthState truthState) {
        log.info("Starting pipeline for book '{}', chapter {}", book.getTitle(), book.nextChapterNumber());
        resetTimings();

        if (book.getChapters() == null || book.getChapters().isEmpty()) {
            log.info("No existing chapters, starting from scratch");
        }

        PipelineContext context = new PipelineContext(book, truthState, config);

        PipelineResult result = pipeline.runFull(context);

        if (result.success()) {
            PipelineContext finalContext = result.updatedContext();
            String finalText = finalContext.getCurrentChapterDraft();
            String writerDraft = finalContext.getWriterDraft();

            Chapter chapter = new Chapter();
            chapter.setNumber(book.nextChapterNumber());
            chapter.setDraftText(writerDraft != null ? writerDraft : finalText);
            chapter.setFinalText(finalText);
            chapter.setAuditResult(finalContext.getAuditResult());
            book.getChapters().add(chapter);
            log.info("Chapter {} added to book '{}' ({} chars, draft {} chars)",
                    chapter.getNumber(), book.getTitle(), finalText.length(),
                    writerDraft != null ? writerDraft.length() : 0);

            updateBookProgress(book, chapter, finalContext);
        }

        return result;
    }

    /** Run partial pipeline (e.g. audit-only on existing chapter) using instance config */
    public PipelineResult runAuditOnly(Book book, TruthState truthState, String chapterText) {
        return runAuditOnly(book, truthState, chapterText, this.config);
    }

    /** Run partial pipeline with explicit project-level config override */
    public PipelineResult runAuditOnly(Book book, TruthState truthState, String chapterText, PipelineConfig projectConfig) {
        resetTimings();
        PipelineContext context = new PipelineContext(book, truthState, projectConfig);
        context.setCurrentChapterDraft(chapterText);
        return pipeline.runPartialByName(context, "Auditor", "Reviser");
    }

    /** Run draft-only pipeline (Architect → Writer, skip quality) */
    public PipelineResult runDraftOnly(Book book, TruthState truthState) {
        log.info("Starting draft-only pipeline for book '{}', chapter {}", book.getTitle(), book.nextChapterNumber());
        resetTimings();

        if (book.getChapters() == null || book.getChapters().isEmpty()) {
            log.info("No existing chapters, starting from scratch");
        }

        PipelineContext context = new PipelineContext(book, truthState, config);
        PipelineResult result = pipeline.runPartialByName(context, "Architect", "Writer");

        if (result.success()) {
            PipelineContext finalContext = result.updatedContext();
            String finalText = finalContext.getCurrentChapterDraft();
            String writerDraft = finalContext.getWriterDraft();

            Chapter chapter = new Chapter();
            chapter.setNumber(book.nextChapterNumber());
            chapter.setDraftText(writerDraft != null ? writerDraft : finalText);
            chapter.setFinalText(finalText);
            book.getChapters().add(chapter);
            log.info("Draft chapter {} added to book '{}' ({} chars)",
                    chapter.getNumber(), book.getTitle(), finalText.length());

            updateBookProgress(book, chapter, finalContext);
        }

        return result;
    }
}
