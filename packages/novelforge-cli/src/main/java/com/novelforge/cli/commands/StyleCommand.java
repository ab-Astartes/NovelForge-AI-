package com.novelforge.cli.commands;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.llm.OpenAiClient;
import com.novelforge.core.models.WritingStyle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * StyleCommand — clone writing style from reference text.
 * Analyzes reference text and generates a WritingStyle profile.
 * Usage: novelforge style clone --reference <file> [--api-key <key>] [--output <file>]
 */
public class StyleCommand {

    public void execute(String[] args) {
        String referencePath = findOption(args, "--reference");
        String apiKey = findOption(args, "--api-key");
        String baseUrl = findOption(args, "--base-url");
        String modelId = findOption(args, "--model");
        String outputPath = findOption(args, "--output");

        if (referencePath == null) {
            System.err.println("Error: --reference <file> is required");
            System.err.println("Usage: novelforge style clone --reference <text_file> [--api-key <key>] [--output <json>]");
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

        try {
            String referenceText = Files.readString(Paths.get(referencePath));

            // Truncate if too long
            if (referenceText.length() > 8000) {
                referenceText = referenceText.substring(0, 8000);
                System.out.println("Reference text truncated to 8000 chars");
            }

            System.out.println("🎭 Analyzing writing style from " + referencePath + " (" + referenceText.length() + " chars)...");

            LlmClient client = new OpenAiClient(apiKey, baseUrl);

            String systemPrompt = """
                你是写作风格分析专家。从参考文本中提取写作风格特征，输出 JSON 格式的风格画像。
                
                分析维度：
                1. narrativeVoice: 叙事视角（第一人称/第三人称/全知）
                2. tone: 语调（严肃/轻松/幽默/黑暗/温暖）
                3. sentenceLength: 句子长度偏好（短句/中等/长句/混合）
                4. vocabularyLevel: 用词水平（日常/文艺/学术/口语）
                5. dialogueStyle: 对话风格（简洁/冗长/含蓄/直接）
                6. descriptionDensity: 描写密度（极简/适中/丰富/过度）
                7. pacingPreference: 节奏偏好（快节奏/中等/慢热）
                8. emotionalRange: 情感范围（冷峻/温和/热烈/多变）
                9. humorLevel: 幽默程度（0-10）
                10. literaryDevices: 常用修辞手法
                
                输出格式：
                { "narrativeVoice": "...", "tone": "...", "sentenceLength": "...",
                  "vocabularyLevel": "...", "dialogueStyle": "...", "descriptionDensity": "...",
                  "pacingPreference": "...", "emotionalRange": "...", "humorLevel": 0-10,
                  "literaryDevices": ["..."], "samplePatterns": ["典型句式1", "典型句式2"] }
                """;

            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", "请分析以下文本的写作风格：\n\n" + referenceText)
            );

            String response = client.chatComplete(messages, modelId, 0.3, 1500);

            System.out.println("\n✅ Style analysis complete:");
            System.out.println(response);

            // Save to file if output specified
            if (outputPath != null) {
                Files.writeString(Paths.get(outputPath), response);
                System.out.println("\nSaved to: " + outputPath);
            }

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    private String findOption(String[] args, String key) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return null;
    }
}
