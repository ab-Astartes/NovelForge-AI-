package com.novelforge.core.pipeline;

import com.novelforge.core.agent.AgentPipeline;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.state.TruthState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PipelineRunner — executes a full or partial writing pipeline for a book.
 * Handles setup, execution, state persistence, and error recovery.
 */
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final AgentPipeline pipeline;
    private final PipelineConfig config;

    public PipelineRunner(PipelineConfig config, ModelRouter router) {
        this.config = config;
        this.pipeline = new AgentPipeline(router);
    }

    /** Run the "write next chapter" pipeline */
    public PipelineResult writeNextChapter(Book book, TruthState truthState) {
        log.info("Starting pipeline for book '{}', chapter {}", book.getTitle(), book.nextChapterNumber());
        PipelineContext context = new PipelineContext(book, truthState, config);

        PipelineResult result = pipeline.runFull(context);

        if (result.success()) {
            // Extract final chapter text from the pipeline context (updated by all agents)
            PipelineContext finalContext = result.updatedContext();
            String finalText = finalContext.getCurrentChapterDraft();

            // Add chapter to book
            Chapter chapter = new Chapter();
            chapter.setNumber(book.nextChapterNumber());
            chapter.setDraftText(context.getCurrentChapterDraft());  // original writer draft
            chapter.setFinalText(finalText);                        // after normalizer/reviser
            chapter.setAuditResult(finalContext.getAuditResult());
            book.getChapters().add(chapter);
            log.info("Chapter {} added to book '{}' ({} chars)", chapter.getNumber(), book.getTitle(), finalText.length());
        }

        return result;
    }

    /** Run partial pipeline (e.g. audit-only on existing chapter) */
    public PipelineResult runAuditOnly(Book book, TruthState truthState, String chapterText) {
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(chapterText);
        // Run only Auditor + Reviser (indexes 7-8)
        return pipeline.runPartial(context, 7, 8);
    }

    /** Run draft-only pipeline (Architect → Writer, skip quality) */
    public PipelineResult runDraftOnly(Book book, TruthState truthState) {
        PipelineContext context = new PipelineContext(book, truthState, config);
        // Run Architect through Writer (indexes 0-3)
        return pipeline.runPartial(context, 0, 3);
    }
}
