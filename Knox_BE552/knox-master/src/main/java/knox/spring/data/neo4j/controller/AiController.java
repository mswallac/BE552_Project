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
import knox.spring.data.neo4j.ai.SequenceTools;

import java.util.function.Function;

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
You are Knox's circuit designer. Every request → one COMBINATORIAL design \
space (multiple enumerable designs).

WORKFLOW
1. Pick the topology. Biosensor = sensor TU (inducer-responsive promoter \
   driving reporter) + optional regulator TU (constitutive → regulator CDS). \
   Toggle / repressilator / logic gate = textbook.
2. Run `search_parts_batch` ONCE with every slot at `limit=5`. Pick 2-3 \
   candidates per slot.
3. Call `importPartsAsGoldbar(tusSpec, spaceName, legend)`. `tusSpec`: TUs \
   5'→3' pipe-separated; each slot `id1[+id2+id3]:role:label`. Respond with \
   ONE short sentence — tool output has the breakdown.
4. For sequence / FASTA asks → `getDesignSequences(spaceID, n)`. For part \
   list only → `enumerateDesigns`.

SEARCH RULES (read carefully — vector search misranks exact IDs)
- If you KNOW a canonical ID (e.g. `BBa_R0040` for PTet), put the ID itself \
  in the `query` field — NOT descriptive keywords. Only fall back to \
  descriptions if the ID query misses. Standard classics always in DB: \
  BBa_R0010, BBa_R0040, BBa_R0051, BBa_C0012, BBa_C0040, BBa_C0051, \
  BBa_I0500, BBa_R0062, BBa_C0061, BBa_C0062, BBa_E0040, BBa_E1010, \
  BBa_B0015, BBa_B0034, BBa_K5060011 (ArsR), BBa_K4767001 (Pars).

HARD RULES — violating any of these breaks the circuit
- NEVER write a part ID in `tusSpec` unless it was returned by a search \
  in this conversation. No prior-knowledge IDs.
- ID-to-name traps (easy silent failures — always verify name matches ID):
    * `BBa_C0040` = TetR. `BBa_C0080` = AraC (NOT TetR).
    * `BBa_C0051` = cI. `BBa_C0179` = LasR (NOT cI).
  If a search returns `C0080` for a "TetR" query or `C0179` for a "cI" \
  query, that's vector-search noise — reject it and retry with the \
  correct ID.
- Cognate pairing: repressor CDS and its controlled promoter must be \
  from the SAME regulatory system. Canonical pairs: LacI↔R0010, \
  TetR↔R0040, cI↔R0051, ArsR↔Pars, LuxR↔pLux. `tetR_Orthologs` \
  promoters (pButR, pTarA, pHlyIIR, pPhlF, pIcaRA, pQacR, …) are NOT \
  TetR-cognate — they only respond to their own ortholog TF. `pL-lac0-1` \
  / `pTac` are LacI-controlled, NOT cI-controlled.
- Host: machinery (promoter/RBS/terminator) must be E. coli-compatible \
  — reject only if `organism` explicitly names a non-E. coli host \
  (B. subtilis, yeast, mammalian, etc.). `organism: unknown` = accept. \
  CDS (regulator/reporter/enzyme) host-agnostic.

If a slot genuinely has no candidate after ONE retry, omit the slot or \
downgrade the topology (e.g. repressilator → toggle) and note it in \
the legend. Honesty over completeness.
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

        // Resolve the MCP `get_parts_batch` callback so SequenceTools can fetch
        // all sequences for an enumerated design space in one round-trip. Null
        // when MCP is unreachable; SequenceTools degrades gracefully.
        Function<List<String>, String> getPartsBatchFn = resolveGetPartsBatch(remoteMcpTools);

        ChatResponse response;
        try {
            response = chatClient
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(prompt)
                .tools(new GroupTools(designSpaceService),
                       new DesignTools(designSpaceService),
                       new OperatorTools(designSpaceService),
                       new GoldbarTools(designSpaceService),
                       new CircuitImportTools(designSpaceService),
                       new SequenceTools(designSpaceService, getPartsBatchFn))
                .toolCallbacks(remoteMcpTools)
                .call().chatResponse();
        } catch (Exception e) {
            // Common failure mode: an MCP tool (usually search_parts against Qdrant)
            // exceeded the 120s timeout, which then poisoned the next LLM response
            // with a non-JSON error string. Instead of 500ing the whole request, tell
            // the user what happened so they can retry or narrow their prompt.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String reason = root.getClass().getSimpleName() + ": " + (root.getMessage() == null ? "" : root.getMessage());
            System.out.println("\nAgent call failed: " + reason);
            return "The design request failed mid-flight — " + reason
                + "<br>Most often this is a slow or stuck MCP tool call. Try a shorter / more specific prompt, or retry.";
        }

        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return "No model output was produced — try asking again with a more specific prompt.";
        }
        String output = response.getResult().getOutput().getText();
        if (output == null) output = "";

        // Spring AI serializes `returnDirect=true` tool output as a JSON
        // string literal (wrapping quotes + `\"`, `\\`, `\n` escapes inside).
        // Proper JSON-decode it back to raw text so embedded HTML attributes
        // (onclick="...", style="...") aren't mangled in the browser.
        if (output.startsWith("\"") && output.endsWith("\"") && output.length() >= 2) {
            try {
                output = new com.fasterxml.jackson.databind.ObjectMapper().readValue(output, String.class);
            } catch (Exception e) {
                // Fallback to the old simple strip if decoding fails.
                output = output.substring(1, output.length() - 1);
            }
        }

        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
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

    // Thin wrapper around the shared static resolver in SequenceTools that
    // also logs the set of discovered MCP tool names (useful when Spring AI
    // renames or prefixes them differently across versions).
    private Function<List<String>, String> resolveGetPartsBatch(ToolCallback[] tools) {
        StringBuilder seen = new StringBuilder();
        for (ToolCallback tc : tools) seen.append(tc.getToolDefinition().name()).append(' ');
        System.out.println("MCP tools discovered: " + seen);
        Function<List<String>, String> fn = SequenceTools.resolveGetPartsBatch(tools);
        if (fn == null) {
            System.out.println("No get_parts_batch tool matched — SequenceTools will report 'unreachable'.");
        }
        return fn;
    }
}
