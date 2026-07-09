package com.novelforge.core.prompt;

import com.novelforge.core.models.*;
import com.novelforge.core.state.TruthState;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.genre.GenreManager;
import com.novelforge.core.models.RevisionPlan;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * PromptBuilder — constructs the system + user message pair for each Agent.
 * Each agent has its own prompt template; this class assembles the context
 * (truth state, genre rules, outline, hook agenda, previous chapter) into
 * a structured prompt that the LLM can process.
 */
public class PromptBuilder {

    /** Maximum prompt length to prevent LLM token overflow (fixes #11: unified truncation) */
    public static final int MAX_PROMPT_LENGTH = 8000;

    private final GenreManager genreManager = new GenreManager();

    /**
     * Build messages for Architect agent.
     * Input: author intent, genre profile.
     * Output: outline + chapter plan.
     */
    public List<Map<String, String>> buildArchitectPrompt(Book book, TruthState state, PipelineConfig config) {
        String system = """
            你是小说大纲架构师。根据作者的意图和题材规则，构建整书大纲和章节规划。
            
            输出格式要求：
            1. 整书大纲（每个章节的核心事件、冲突点、hook）
            2. 当前章节的详细计划（场景、角色、事件、hook agenda）
            
            规则：
            - 每章必须有一个核心冲突/悬念推进
            - 大纲必须体现升级体系的递进
            - 遵守题材特定规则和套路
            """;

        String user = String.format("""
            ## 作者意图
            %s
            
            ## 题材
            %s
            
            ## 现有大纲
            %s
            
            ## 已写章数
            %d
            
            请构建/更新大纲，并为第 %d 章生成详细章节计划。
            """,
                nullSafe(book.getAuthorIntent()),
                nullSafe(book.getGenre()),
                nullSafe(book.getOutline()),
                book.getChapters().size(),
                book.nextChapterNumber()
        );

        return messages(system, user);
    }

    /**
     * Build messages for Planner agent.
     * Input: outline + chapter intent from Architect.
     * Output: hook agenda for this chapter.
     */
    public List<Map<String, String>> buildPlannerPrompt(Book book, TruthState state,
                                                         String architectOutput, PipelineConfig config) {
        String system = """
            你是章节规划师。根据大纲和当前章节意图，生成具体的 hook agenda。
            
            Hook 类型：
            - mustAdvance: 本章必须推进的已有悬念（从 hooks.json 的 mustAdvance 列表）
            - newHook: 本章可以埋下的新悬念
            - eligibleResolve: 可以在本章部分解决的悬念
            
            输出格式：
            1. 本章 mustAdvance 列表（从状态文件中的必须推进项）
            2. 本章 newHook 建议（2-3 个新悬念点）
            3. eligibleResolve 建议
            4. 章节节奏规划（起承转合）
            """;

        String user = String.format("""
            ## 大纲架构师输出
            %s
            
            ## 当前必须推进的悬念
            %s
            
            ## 已有角色状态
            %s
            
            ## 第 %d 章意图
            请为第 %d 章生成 hook agenda 和节奏规划。
            """,
                truncateShort(architectOutput, 4000),
                state.hooks().getMustAdvanceSummary(),
                state.characters().getSummary(),
                book.nextChapterNumber(),
                book.nextChapterNumber()
        );

        return messages(system, user);
    }

    /**
     * Build messages for Composer agent.
     * Input: all context from previous agents + truth state.
     * Output: assembled context package for Writer.
     */
    public List<Map<String, String>> buildComposerPrompt(Book book, TruthState state,
                                                          String plannerOutput, PipelineConfig config) {
        Chapter prevChapter = book.getChapters().isEmpty() ? null :
                book.getChapters().get(book.getChapters().size() - 1);

        String system = """
            你是上下文组装器。将所有写作规则、状态信息、hook agenda 编译成一个
            紧凑但完整的上下文包，供 Writer 直接使用。
            
            上下文包必须包含：
            1. 角色信息（只包含本章出场角色的关键属性）
            2. 世界观规则（只包含本章相关的）
            3. Hook agenda（必须推进 + 新埋悬念）
            4. 上一章末尾 500 字（衔接参考）
            5. 题材规则栈
            
            输出格式：直接给出组装后的完整上下文（JSON 或结构化文本）。
            """;

        String prevEnd = prevChapter != null ?
                truncateShort(prevChapter.getFinalText() != null ? prevChapter.getFinalText() : prevChapter.getDraftText(), 500) :
                "（这是第一章，无前文衔接）";

        String user = String.format("""
            ## 规划师输出
            %s
            
            ## 上一章末尾
            %s
            
            ## 角色状态
            %s
            
            ## 世界观
            %s
            
            ## 题材: %s
            
            请组装第 %d 章的完整写作上下文包。
            """,
                truncateShort(plannerOutput, 4000),
                prevEnd,
                state.characters().getSummary(),
                state.world().getSummary(),
                nullSafe(book.getGenre()),
                book.nextChapterNumber()
        );

        return messages(system, user);
    }

    /**
     * Build messages for Writer agent.
     * Input: composed context from Composer.
     * Output: chapter draft text (2000-4000 words).
     */
    public List<Map<String, String>> buildWriterPrompt(Book book, TruthState state,
                                                        String composedContext, PipelineConfig config) {
        GenreProfile genreProfile = genreManager.getGenre(book.getGenre());

        StringBuilder genreRules = new StringBuilder();
        if (genreProfile != null) {
            genreRules.append("\n## 题材详细规则\n");
            if (genreProfile.getOutlineTemplate() != null)
                genreRules.append("- 结构模板: ").append(genreProfile.getOutlineTemplate()).append("\n");
            if (genreProfile.getTropes() != null && genreProfile.getTropes().length > 0)
                genreRules.append("- 可用套路: ").append(String.join(", ", genreProfile.getTropes())).append("\n");
            if (genreProfile.getAvoidanceList() != null && genreProfile.getAvoidanceList().length > 0)
                genreRules.append("- 必须避免: ").append(String.join(", ", genreProfile.getAvoidanceList())).append("\n");
            if (genreProfile.getNamingConvention() != null)
                genreRules.append("- 命名规范: ").append(genreProfile.getNamingConvention()).append("\n");
            if (genreProfile.getPacingRules() != null)
                genreRules.append("- 节奏规则: ").append(genreProfile.getPacingRules()).append("\n");
            if (genreProfile.getUpgradeSystem() != null)
                genreRules.append("- 升级体系: ").append(genreProfile.getUpgradeSystem()).append("\n");
        }

        // WritingStyle injection
        WritingStyle style = book.getStyle();
        StringBuilder styleRules = new StringBuilder();
        if (style != null) {
            styleRules.append("\n## 写作风格要求\n");
            if (style.getVocabularyPattern() != null && !style.getVocabularyPattern().isEmpty())
                styleRules.append("- 用词风格: ").append(style.getVocabularyPattern()).append("\n");
            if (style.getSentenceStructure() != null && !style.getSentenceStructure().isEmpty())
                styleRules.append("- 句式结构: ").append(style.getSentenceStructure()).append("\n");
            if (style.getPacingPattern() != null && !style.getPacingPattern().isEmpty())
                styleRules.append("- 节奏偏好: ").append(style.getPacingPattern()).append("\n");
            if (style.getDialogueStyle() != null && !style.getDialogueStyle().isEmpty())
                styleRules.append("- 对话风格: ").append(style.getDialogueStyle()).append("\n");
            if (style.getDescriptionStyle() != null && !style.getDescriptionStyle().isEmpty())
                styleRules.append("- 描写风格: ").append(style.getDescriptionStyle()).append("\n");
            if (style.getReferenceSample() != null && !style.getReferenceSample().isEmpty())
                styleRules.append("- 参考样例: ").append(truncateShort(style.getReferenceSample(), 300)).append("\n");
        }

        String system = """
            你是小说写手。根据组装好的上下文包，创作本章内容。
            
            创作要求：
            1. 字数范围: %d - %d 字
            2. 严格遵守 hook agenda（必须推进指定悬念）
            3. 角色言行必须与状态文件一致
            4. 遵守题材规则和风格要求
            5. 章节结尾必须有悬念或转折，让读者想继续看
            
            写作原则：
            - 先写冲突，再写解决
            - 每个场景要有画面感和动作
            - 对话要有角色个性，不要流水账
            - 避免过度描写心理活动，用行动展现
            %s%s
            """;

        String formattedSystem = String.format(system, config.getChapterWordsMin(), config.getChapterWordsMax(), genreRules.toString(), styleRules.toString());

        String user = String.format("""
            ## 写作上下文包
            %s
            
            请创作第 %d 章完整内容。
            """,
                truncate(composedContext),
                book.nextChapterNumber()
        );

        return messages(formattedSystem, user);
    }

    /**
     * Build messages for Observer agent.
     * Input: chapter draft text.
     * Output: 9-category fact extraction.
     */
    public List<Map<String, String>> buildObserverPrompt(Book book, TruthState state,
                                                          String chapterDraft, PipelineConfig config) {
        String system = """
            你是事实观察员。从章节文本中提取所有需要追踪的事实变化。
            
            提取 9 类事实：
            1. character_new — 新角色出场
            2. character_change — 角色属性变化（实力、关系、位置）
            3. world_new — 新地点/物品/规则出现
            4. world_change — 世界状态变化
            5. timeline_event — 时间线事件
            6. hook_new — 新悬念被埋下
            7. hook_advance — 已有悬念被推进
            8. hook_resolve — 悬念被解决
            9. inconsistency — 与现有状态矛盾的事实
            
            输出格式：JSON，每类一个数组，每个条目包含具体描述。
            """;

        String user = String.format("""
            ## 第 %d 章文本
            %s
            
            ## 现有角色状态摘要
            %s
            
            ## 现有世界观摘要
            %s
            
            请提取所有事实变化。
            """,
                book.nextChapterNumber(),
                truncateShort(chapterDraft, 6000),
                state.characters().getSummary(),
                state.world().getSummary()
        );

        return messages(system, user);
    }

    /**
     * Build messages for Reflector agent.
     * Input: Observer's extraction.
     * Output: statePatch operations + hookOps.
     */
    public List<Map<String, String>> buildReflectorPrompt(Book book, TruthState state,
                                                           String observerOutput, PipelineConfig config) {
        String system = """
            你是状态反射器。将观察员提取的事实变化转化为具体的状态更新操作。
            
            输出两类操作：
            1. hookOps — 悬念操作列表
               每个操作: { type: UPSERT|MENTION|RESOLVE|DEFER, hookId, description, priority }
            2. statePatch — 状态补丁
               characterDelta: 需要更新/新增的角色属性
               worldDelta: 需要更新/新增的世界观条目
               timelineDelta: 需要添加的时间线事件
            
            注意：只输出增量变化，不要重复已有信息。
            """;

        String user = String.format("""
            ## 观察员提取结果
            %s
            
            ## 当前悬念状态
            %s
            
            请生成 hookOps 和 statePatch。
            """,
                truncateShort(observerOutput, 4000),
                state.hooks().getSummary()
        );

        return messages(system, user);
    }

    /**
     * Build messages for Normalizer agent.
     * Input: chapter draft text.
     * Output: length-adjusted text (within word count range).
     */
    public List<Map<String, String>> buildNormalizerPrompt(Book book, TruthState state,
                                                            String chapterDraft, PipelineConfig config) {
        int currentWords = TextUtils.estimateChineseWordCount(chapterDraft);
        String system = """
            你是文本规范化器。调整章节文本长度至目标范围。
            
            当前字数: %d
            目标范围: %d - %d 字
            
            操作规则：
            - 如果超出上限：删减冗余描写和重复内容，保留核心冲突和悬念
            - 如果低于下限：补充场景细节、角色互动、环境描写
            - 不要改变剧情走向和核心事件
            - 保持叙事节奏和 hook 结构不变
            """;

        String formattedSystem = String.format(system, currentWords, config.getChapterWordsMin(), config.getChapterWordsMax());

        String user = String.format("""
            ## 第 %d 章文本
            %s
            
            请规范化文本长度。
            """,
                book.nextChapterNumber(),
                chapterDraft
        );

        return messages(formattedSystem, user);
    }

    /**
     * Build messages for Auditor agent.
     * Input: chapter text + chapter intent.
     * Output: 33-dimension audit scores.
     */
    public List<Map<String, String>> buildAuditorPrompt(Book book, TruthState state,
                                                         String chapterText, PipelineConfig config) {
        String system = """
            你是小说质量审计员。对章节文本进行 33 维质量检查。
            
            评分维度（每项 0-10 分）：
            
            A. 节奏（5维）：flow, variation, tensionCurve, sceneLengthBalance, transitionSmoothness
            B. 对话（5维）：naturalness, characterVoice, subtext, tagVariety, actionBeats
            C. 世界观（5维）：consistency, detailLevel, sensoryImmersion, powerSystemLogic, settingFreshness
            D. 大纲（5维）：chapterIntentMatch, hookFulfillment, progressionDirection, characterArcAlignment, plotTwistSetup
            E. 风格+Hook（5+5维）：vocabularyConsistency, sentenceVariety, toneConsistency, genreVoice, descriptionBalance;
                                    mustAdvanceHandled, newHooksPlanted, staleDebt, burstDetection, resolutionQuality
            F. 反AIGC（3维）：repetitivePatterns, genericExpressions, overlyBalancedStructure
            
            输出格式：JSON
            { "scores": { "dimension.name": score }, "criticalIssues": [...], "warnings": [...] }
            """;

        String user = String.format("""
            ## 第 %d 章文本
            %s
            
            ## 本章意图
            %s
            
            ## 题材: %s
            
            请进行 33 维审计评分。
            """,
                book.nextChapterNumber(),
                truncateShort(chapterText, 6000),
                "（由大纲和规划定义）",
                nullSafe(book.getGenre())
        );

        return messages(system, user);
    }

    /**
     * Build messages for Reviser agent — with explicit revision mode.
     */
    public List<Map<String, String>> buildReviserPrompt(Book book, TruthState state,
                                                         String chapterText, AuditResult auditResult,
                                                         PipelineConfig config, RevisionPlan.Mode mode) {
        String modeDescription = switch (mode) {
            case POLISH -> "整体润色 — 只做微调，不改变核心内容。修复文笔、节奏小问题。";
            case SPOT_FIX -> "定点修复 — 只改有问题的段落，保留好的部分不动。";
            case REWRITE -> "重写 — 严重问题需要大改。可以重新组织场景顺序、重写段落。";
            case ANTI_DETECT -> "反AIGC检测修复 — 消除重复模式、泛化表达、过于均衡的结构。增加人味和意外感。";
        };
        String criticalIssues = auditResult.getCriticalIssues() != null ?
                String.join("\n", auditResult.getCriticalIssues()) : "无";
        String warnings = auditResult.getWarnings() != null ?
                String.join("\n", auditResult.getWarnings()) : "无";

        String system = """
            你是修订者。根据审计结果修复章节文本中的问题。
            
            当前修复模式: %s
            
            修复原则：
            1. 优先修复 criticalIssues
            2. 尽量保留好的部分，只改有问题的
            3. 修复后重新检查是否引入新问题
            4. 保持叙事连贯性
            """.formatted(modeDescription);

        String user = String.format("""
            ## 第 %d 章文本
            %s
            
            ## 审计评分: %.1f/10
            ## 必须修复的问题:
            %s
            ## 建议修复的问题:
            %s
            
            请修复章节文本。
            """,
                book.nextChapterNumber(),
                truncateShort(chapterText, 6000),
                auditResult.getOverallScore(),
                criticalIssues,
                warnings
        );

        return messages(system, user);
    }

    // --- Helper methods ---

    private static List<Map<String, String>> messages(String system, String user) {
        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", system));
        msgs.add(Map.of("role", "user", "content", user));
        return msgs;
    }

    private static String nullSafe(String s) {
        return s == null ? "（未指定）" : s;
    }

    /** Unified truncation — truncates to given max length with marker (fixes #11).
     *  Default: MAX_PROMPT_LENGTH for main content; shorter limits for sub-sections. */
    private String truncate(String text, int max) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() > max) {
            return text.substring(0, max) + "\n[...truncated]";
        }
        return text;
    }

    /** Convenience: truncate to MAX_PROMPT_LENGTH */
    private String truncate(String text) {
        return truncate(text, MAX_PROMPT_LENGTH);
    }

    /** Convenience: truncate to custom short limit */
    private String truncateShort(String text, int max) {
        return truncate(text, max);
    }

    /**
     * Build messages for Architect agent — incremental update (subsequent chapters).
     * Only supplements/fixes the chapter plan, does NOT rebuild the entire outline.
     */
    public List<Map<String, String>> buildArchitectIncrementalPrompt(Book book, TruthState state, PipelineConfig config) {
        String system = """
            你是小说大纲架构师。现在需要为下一章补充/修正章节计划。
            
            重要：已有大纲不要重写！只做以下工作：
            1. 检查现有大纲是否需要微调（仅当前章和下一章）
            2. 为即将写作的下一章生成详细的章节计划
            3. 如果发现前面章节规划有问题，给出修正建议
            
            输出格式：
            - 章节计划：场景、角色、事件、hook agenda
            - 如需修正大纲，只输出修正部分
            - 如大纲无需修正，直接输出章节计划即可
            """;

        String user = String.format("""
            ## 作者意图
            %s
            
            ## 题材
            %s
            
            ## 已有大纲（请在此基础上补充，不要重写）
            %s
            
            ## 已写章数
            %d
            
            ## 当前角色状态
            %s
            
            ## 当前悬念
            %s
            
            请为第 %d 章生成详细章节计划，并检查大纲是否需要微调。
            """,
                nullSafe(book.getAuthorIntent()),
                nullSafe(book.getGenre()),
                truncateShort(book.getOutline(), 6000),
                book.getChapters().size(),
                state.characters().getSummary(),
                state.hooks().getMustAdvanceSummary(),
                book.nextChapterNumber()
        );

        return messages(system, user);
    }

    /** Estimate max tokens for target word count (Chinese text: ~1.5 tokens per char, 50% buffer) */
    public int estimateMaxTokens(int chapterWordsMax) {
        int estimatedMaxTokens = (int) (chapterWordsMax * 1.5 * 1.5);
        return Math.max(4000, estimatedMaxTokens);
    }

    // estimateChineseWords moved to TextUtils.estimateChineseWordCount
}
