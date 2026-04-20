package knox.spring.data.neo4j.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import knox.spring.data.neo4j.ai.GroupTools;
import knox.spring.data.neo4j.ai.DesignTools;
import knox.spring.data.neo4j.ai.OperatorTools;
import knox.spring.data.neo4j.ai.GoldbarTools;
import knox.spring.data.neo4j.ai.CircuitImportTools;

import knox.spring.data.neo4j.services.DesignSpaceService;

@RestController
public class AiController {

    private static final String DISABLED = "disabled";

    // System prompt — steers short user requests like "make me an arsenic biosensor"
    // through the full combinatorial-design workflow. Deliberately contains NO
    // hardcoded part IDs; every part must come from a live search_parts call so
    // stale/defunct IDs never sneak in. Tool descriptions carry the parse-format
    // details; this prompt decides WHEN to call WHICH tool.
    private static final String SYSTEM_PROMPT = """
You are the circuit designer for Knox, a genetic-design-space repository. When \
the user asks for ANY genetic circuit (biosensor, toggle switch, logic gate, \
repressilator, etc.), your job is to produce a COMBINATORIAL design space — \
multiple enumerable designs, not a single point design.

Workflow for every circuit-design request:

1. Infer the architecture from biology:
   - Biosensor: TU1 constitutively expresses the regulator/sensor CDS; TU2 has \
the target-responsive promoter driving a reporter. Each TU is \
promoter -> RBS -> CDS -> terminator.
   - Toggle switch / repressilator / logic gate: use the textbook topology.

2. For EVERY slot — functional (sensor promoter, regulator CDS, reporter CDS) \
AND structural (RBS, terminator, constitutive promoter) — call `search_parts` \
with an explicit `part_type` filter (`promoter`, `coding`, `reporter`, \
`regulator`, `terminator`, `rbs`) and pick 2-3 real candidates. Do NOT invent \
part IDs. Do NOT name specific BBa_ IDs from memory. Only use IDs returned by \
`search_parts` in THIS conversation.

3. Pack multiple candidates per slot using `+` separators in the tusSpec, so \
each functional slot contributes a factor to the design count. Aim for at \
least 6 enumerable designs total.

4. Call `importPartsAsGoldbar` with:
   - `tusSpec`: TUs in 5'->3' order, `|`-separated; each slot is \
`id1[+id2+id3]:role:label` where label is a short human purpose tag like \
`arsenic_sensor`, `ArsR_regulator`, `GFP_reporter`, `RBS`, `terminator`, \
`constitutive_promoter`.
   - `legend`: 2-3 sentence plain-English explanation of the circuit's \
mechanism (what represses what, what activates what, what the output is).

5. After the tool returns, respond with AT MOST one short sentence — the \
tool's response already contains the full breakdown; don't restate it.

For non-design queries (list parts, show part details, describe an existing \
design space), use the other tools directly and answer concisely.
""";

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

    // Remote MCP tools (e.g. MCPGeneBank). Optional — when no MCP server is reachable
    // this list is empty and Knox still functions with just its local tools.
    @Autowired(required = false)
    private List<ToolCallbackProvider> mcpToolProviders = List.of();

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

        ToolCallback[] remoteMcpTools;
        try {
            remoteMcpTools = mcpToolProviders.stream()
                .flatMap(p -> java.util.Arrays.stream(p.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
            System.out.println("Remote MCP tools available: " + remoteMcpTools.length);
        } catch (Exception e) {
            // MCP server unreachable (e.g. MCPGeneBank not running) — fall back to local tools only.
            System.out.println("MCP tool discovery failed: " + e.getMessage() + " — continuing with local tools only.");
            remoteMcpTools = new ToolCallback[0];
        }

        ChatResponse response = chatClient
            .prompt()
            .system(SYSTEM_PROMPT)
            .user(prompt)
            .tools(new GroupTools(designSpaceService),
                   new DesignTools(designSpaceService),
                   new OperatorTools(designSpaceService),
                   new GoldbarTools(designSpaceService),
                   new CircuitImportTools(designSpaceService))
            .toolCallbacks(remoteMcpTools)
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
