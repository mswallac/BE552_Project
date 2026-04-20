package knox.spring.data.neo4j.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.*;

import knox.spring.data.neo4j.ai.GroupTools;
import knox.spring.data.neo4j.ai.DesignTools;
import knox.spring.data.neo4j.ai.OperatorTools;
import knox.spring.data.neo4j.ai.GoldbarTools;
import knox.spring.data.neo4j.ai.PartsSearchTools;

import knox.spring.data.neo4j.services.DesignSpaceService;

@RestController
public class AiController {

    private static final String DISABLED = "disabled";

    private record Pricing(double promptPer1K, double completionPer1K) {}

    // USD per 1K tokens, as of Apr 2026.
    private static final Map<String, Pricing> MODEL_PRICING = Map.of(
        "gpt-3.5-turbo",            new Pricing(0.0005, 0.0015),
        "gpt-4o",                   new Pricing(0.0025, 0.0100),
        "gpt-4o-mini",              new Pricing(0.00015, 0.00060),
        "claude-3-haiku-20240307",  new Pricing(0.00025, 0.00125),
        "claude-3-5-sonnet-latest", new Pricing(0.003, 0.015),
        "gemini-2.0-flash",         new Pricing(0.0001, 0.0004),
        "gemini-2.5-flash",         new Pricing(0.00015, 0.0006),
        "gemini-2.5-pro",           new Pricing(0.00125, 0.010)
    );

    @Value("${spring.ai.model.chat:}")
    private String activeProvider;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Value("${spring.ai.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${spring.ai.google.genai.api-key:}")
    private String geminiApiKey;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String openAiModel;

    @Value("${spring.ai.anthropic.chat.options.model:}")
    private String anthropicModel;

    @Value("${spring.ai.google.genai.chat.options.model:}")
    private String geminiModel;

    private final ChatModel chatModel;
    private final DesignSpaceService designSpaceService;

    public AiController(ChatClient.Builder chatClientBuilder, ChatModel chatModel, DesignSpaceService designSpaceService) {
        this.chatModel = chatModel;
        this.designSpaceService = designSpaceService;
    }

    @PostMapping("/agent")
    public String agent(@RequestParam(value = "prompt", required = true) String prompt,
            @RequestParam(value = "includeCost", required = false, defaultValue = "true") boolean includeCost) {

        String missingKeyError = checkActiveProviderKey();
        if (missingKeyError != null) {
            return missingKeyError;
        }

        System.out.println("\n\nReceived prompt: " + prompt);

        ChatClient chatClient = ChatClient.create(chatModel);

        ChatResponse response = chatClient
            .prompt(prompt)
            .tools(new GroupTools(designSpaceService),
                   new DesignTools(designSpaceService),
                   new OperatorTools(designSpaceService),
                   new GoldbarTools(designSpaceService),
                   new PartsSearchTools())
            .call().chatResponse();

        String output = response.getResult().getOutput().getText();

        // Remove surrounding quotes if present (Occurs when tool has returnDirect = true)
        if (output.startsWith("\"") && output.endsWith("\"")) {
            output = output.substring(1, output.length() - 1);
        }

        Usage usage = response.getMetadata().getUsage();
        int promptTokens = usage == null ? 0 : usage.getPromptTokens();
        int completionTokens = usage == null ? 0 : usage.getCompletionTokens();
        int totalTokens = usage == null ? 0 : usage.getTotalTokens();

        System.out.printf("\nProvider: %s | Model: %s%n", activeProvider, activeModel());
        System.out.printf("Tokens:\nPrompt: %d, Completion: %d, Total: %d%n", promptTokens, completionTokens, totalTokens);

        Pricing pricing = MODEL_PRICING.get(activeModel());
        double cost = pricing == null
            ? 0.0
            : (promptTokens / 1000.0) * pricing.promptPer1K() + (completionTokens / 1000.0) * pricing.completionPer1K();
        String costNote = pricing == null ? " (unknown model pricing)" : "";
        System.out.printf("Estimated cost: $%.4f%s%n", cost, costNote);

        if (includeCost) {
            output = output + "<br><br>Provider: " + activeProvider + " (" + activeModel() + ")"
                    + "<br>Tokens: Prompt: " + promptTokens + ", Completion: " + completionTokens + ", Total: " + totalTokens
                    + "<br>Estimated cost: $" + String.format("%.4f", cost) + costNote;
        }

        System.out.println("\n\nAI response: " + output);
        return output;
    }

    private String checkActiveProviderKey() {
        String provider = activeProvider == null ? "" : activeProvider;
        return switch (provider) {
            case "openai" -> isDisabled(openAiApiKey)
                ? "Error: Please set 'OPENAI_API_KEY' in environment variables to use AI Chat Features."
                : null;
            case "anthropic" -> isDisabled(anthropicApiKey)
                ? "Error: Please set 'CLAUDE_API_KEY' in environment variables to use AI Chat Features."
                : null;
            case "google-genai" -> isDisabled(geminiApiKey)
                ? "Error: Please set 'GEMINI_API_KEY' in environment variables to use AI Chat Features."
                : null;
            default -> "Error: Unsupported spring.ai.model.chat='" + provider
                + "'. Expected one of: openai, anthropic, google-genai.";
        };
    }

    private String activeModel() {
        String provider = activeProvider == null ? "" : activeProvider;
        return switch (provider) {
            case "openai" -> openAiModel;
            case "anthropic" -> anthropicModel;
            case "google-genai" -> geminiModel;
            default -> "";
        };
    }

    private static boolean isDisabled(String key) {
        return key == null || key.isBlank() || DISABLED.equals(key);
    }
}
