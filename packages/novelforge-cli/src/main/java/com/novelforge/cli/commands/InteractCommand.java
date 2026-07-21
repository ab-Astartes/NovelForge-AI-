package com.novelforge.cli.commands;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.pipeline.PipelineRunner;
import com.novelforge.core.project.BookProject;
import com.novelforge.core.state.TruthState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * InteractCommand — interactive dialogue mode with the novel's world/characters.
 * Allows users to "talk" to characters, explore world details, suggest plot changes.
 * Usage: novelforge interact --book <path> [--api-key <key>]
 */
public class InteractCommand {

    /**
     * Shared Scanner for System.in — never closed to avoid breaking stdin.
     */
    private static final Scanner SHARED_SCANNER = new Scanner(System.in);

    private List<String> conversationHistory = new ArrayList<>();

    public void execute(String[] args) {
        String bookPath = findOption(args, "--book");
        String apiKey = findOption(args, "--api-key");
        String baseUrl = findOption(args, "--base-url");
        String modelId = findOption(args, "--model");

        if (bookPath == null) {
            System.err.println("Error: --book <path> is required");
            return;
        }

        if (apiKey == null) apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null) apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: No API key. Use --api-key or set OPENAI_API_KEY");
            return;
        }
        if (baseUrl == null) baseUrl = System.getenv().getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1");
        if (modelId == null) modelId = "gpt-4o";

        Path bookDir = Paths.get(bookPath);

        try {
            Book book = BookProject.loadBook(bookDir);
            TruthState state = new TruthState(bookDir);

            ModelRouter router = new ModelRouter(new ModelRouter.ModelConfig("openai", modelId, baseUrl, apiKey));
            LlmClient client = router.getClientForAgent("Interact");  // fixes #G5: use ModelRouter instead of standalone OpenAiClient

            System.out.println("📖 Interactive mode for '" + book.getTitle() + "' (genre: " + book.getGenre() + ")");
            System.out.println("   Type your questions/suggestions. Commands: /status, /characters, /world, /hooks, /quit");

            // Use shared scanner — never close System.in


            while (true) {
                System.out.print("\n> ");
                String input = SHARED_SCANNER.nextLine().trim();

                if (input.isEmpty()) continue;
                if (input.equals("/quit") || input.equals("/exit")) {
                    System.out.println("Goodbye! 📖");
                    break;
                }

                switch (input) {
                    case "/status" -> {
                        System.out.println("Book: " + book.getTitle());
                        System.out.println("Chapters: " + book.getChapters().size());
                        System.out.println("Next chapter: " + book.nextChapterNumber());
                    }
                    case "/characters" -> System.out.println(state.characters().getSummary());
                    case "/world" -> System.out.println(state.world().getSummary());
                    case "/hooks" -> System.out.println(state.hooks().getSummary());
                    default -> {
                        // Send to LLM as an interactive prompt with conversation history
                        String systemPrompt = buildInteractSystemPrompt(book, state);
                        String context = conversationHistory.stream()
                            .skip(Math.max(0, conversationHistory.size() - 10))
                            .collect(Collectors.joining("\n"));

                        List<Map<String, String>> messages = new ArrayList<>();
                        messages.add(Map.of("role", "system", "content", systemPrompt));
                        if (!context.isEmpty()) {
                            messages.add(Map.of("role", "system", "content", "对话历史:\n" + context));
                        }
                        messages.add(Map.of("role", "user", "content", input));
                        String response = client.chatComplete(messages, router.getModelForAgent("Interact"), 0.8, 1000);  // fixes #G5
                        System.out.println("\n" + response);
                        conversationHistory.add("User: " + input);
                        conversationHistory.add("AI: " + response);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    private String buildInteractSystemPrompt(Book book, TruthState state) {
        return String.format("""
            你是小说世界交互助手。帮助作者与小说世界互动。
            
            小说: %s (%s)
            已写: %d 章
            
            角色状态:
            %s
            
            世界观:
            %s
            
            悬念:
            %s
            
            你可以:
            1. 让角色"说话"（以角色视角回答问题）
            2. 建议剧情发展方向
            3. 解释世界观设定
            4. 分析角色关系
            5. 探讨悬念走向
            
            请以沉浸式方式回应，保持与小说世界一致。
            """,
                book.getTitle(), book.getGenre(),
                book.getChapters().size(),
                state.characters().getSummary(),
                state.world().getSummary(),
                state.hooks().getSummary()
        );
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

