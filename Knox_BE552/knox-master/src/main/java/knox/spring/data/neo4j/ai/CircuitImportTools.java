package knox.spring.data.neo4j.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import knox.spring.data.neo4j.services.DesignSpaceService;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Converts a CircuitDesign JSON (as returned by MCPGeneBank's build_from_template /
 * design_circuit MCP tools) into a GOLDBAR expression + categories JSON and imports
 * it into Knox as a new design space. This is the "missing GOLDBAR emitter" that
 * connects MCPGeneBank's circuit assembly to Knox's design-space graph view.
 *
 * Expected JSON shape (from _format_circuit_design in MCPGeneBank/mcp_server.py):
 *   {
 *     "circuit_name": "...",
 *     "transcription_units": [
 *       { "name": "...", "parts": [ { "part_id": "...", "name": "...", "type": "...", "sequence": "..." }, ... ] },
 *       ...
 *     ],
 *     ...
 *   }
 */
public class CircuitImportTools {

    private static final Pattern ATOM_SAFE = Pattern.compile("[^A-Za-z0-9_]");

    private final DesignSpaceService designSpaceService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CircuitImportTools(DesignSpaceService designSpaceService) {
        this.designSpaceService = designSpaceService;
    }

    @Tool(description =
        "Convert a CircuitDesign JSON (from MCPGeneBank's build_from_template or design_circuit tools) " +
        "into a GOLDBAR expression + categories JSON and import it into Knox as a new design space. " +
        "IMPORTANT: pass the FULL UNMODIFIED JSON string returned by the MCPGeneBank tool — it must contain " +
        "a top-level `transcription_units` array. Do not summarize, truncate, or reshape it. Returns the " +
        "new design space ID, which becomes visible in Knox's graph view at http://localhost:8080. Use " +
        "this tool after calling a MCPGeneBank circuit-building tool to make the assembled circuit " +
        "viewable as a graph.",
        returnDirect = true)
    String importCircuitAsGoldbar(
            @ToolParam(description = "Full unmodified CircuitDesign JSON string from MCPGeneBank — must contain top-level `transcription_units` array") String circuitJson,
            @ToolParam(description = "Name for the new Knox design space (e.g. 'arsenic_biosensor_v1'). Must match [A-Za-z0-9_-]+.") String spaceName) {

        System.out.println("\nAI: importCircuitAsGoldbar spaceName='" + spaceName + "'");
        System.out.println("circuitJson (first 400 chars): "
            + (circuitJson == null ? "null" : circuitJson.substring(0, Math.min(400, circuitJson.length()))));

        if (circuitJson == null || circuitJson.isBlank()) {
            return "Error: circuitJson is empty. Call a MCPGeneBank circuit-building tool first, then pass its full JSON output here.";
        }
        if (spaceName == null || spaceName.isBlank()) {
            return "Error: spaceName is required.";
        }

        JsonNode root;
        try {
            root = mapper.readTree(circuitJson);
        } catch (Exception e) {
            return "Error: could not parse circuitJson. " + e.getMessage();
        }

        JsonNode tus = root.path("transcription_units");
        if (!tus.isArray() || tus.isEmpty()) {
            return "Error: circuitJson has no `transcription_units` array. Is this a MCPGeneBank CircuitDesign?";
        }

        List<String> tuExpressions = new ArrayList<>();
        JSONObject categories = new JSONObject();

        for (JsonNode tu : tus) {
            JsonNode parts = tu.path("parts");
            if (!parts.isArray() || parts.isEmpty()) continue;

            List<String> tuAtoms = new ArrayList<>();
            for (JsonNode part : parts) {
                String partId = textOrBlank(part, "part_id");
                String partName = textOrBlank(part, "name");
                String partType = textOrBlank(part, "type");
                if (partId.isBlank()) partId = partName;
                if (partId.isBlank()) continue;

                String atom = safeAtom(partId);
                tuAtoms.add(atom);

                // Register this atom in categories: role -> [componentID]
                String role = mapTypeToRole(partType);
                if (!categories.has(atom)) {
                    categories.put(atom, new JSONObject());
                }
                JSONObject atomEntry = categories.getJSONObject(atom);
                if (!atomEntry.has(role)) {
                    atomEntry.put(role, new JSONArray());
                }
                JSONArray ids = atomEntry.getJSONArray(role);
                if (!containsString(ids, partId)) {
                    ids.put(partId);
                }
            }
            if (!tuAtoms.isEmpty()) {
                tuExpressions.add(String.join(" . ", tuAtoms));
            }
        }

        if (tuExpressions.isEmpty()) {
            return "Error: no usable parts found in transcription_units.";
        }

        // Join multiple TUs with `.` (simple cascade). Parenthesize each TU for clarity.
        String goldbar = tuExpressions.size() == 1
            ? tuExpressions.get(0)
            : "(" + String.join(") . (", tuExpressions) + ")";

        String safeSpaceId = safeAtom(spaceName);
        try {
            designSpaceService.importGoldbar(goldbar, categories, safeSpaceId,
                "" /* groupID */, 1.0 /* weight */, false /* verbose */);
        } catch (Exception e) {
            return "Error importing GOLDBAR into Knox: " + e.getMessage()
                + "<br>GOLDBAR was: " + goldbar
                + "<br>Categories keys: " + categories.keySet();
        }

        return "Imported design space <b>" + safeSpaceId + "</b><br>"
            + "GOLDBAR: <code>" + goldbar + "</code><br>"
            + "Parts: " + categories.keySet().size() + " atoms, "
            + tuExpressions.size() + " transcription unit(s).<br>"
            + "View the graph at <a href='http://localhost:8080' target='_blank'>http://localhost:8080</a>.";
    }

    private static String textOrBlank(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }

    private static String safeAtom(String raw) {
        if (raw == null) return "x";
        String cleaned = ATOM_SAFE.matcher(raw).replaceAll("_");
        if (cleaned.isBlank()) return "x";
        // GOLDBAR atoms can start with a digit per the grammar, but be safe.
        if (Character.isDigit(cleaned.charAt(0))) cleaned = "p_" + cleaned;
        return cleaned;
    }

    private static String mapTypeToRole(String partType) {
        if (partType == null) return "engineered_region";
        String t = partType.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "promoter" -> "promoter";
            case "rbs", "ribosome_entry_site" -> "ribosome_entry_site";
            case "cds", "gene", "reporter", "regulator" -> "CDS";
            case "terminator" -> "terminator";
            case "ribozyme" -> "ribozyme";
            default -> "engineered_region";
        };
    }

    private static boolean containsString(JSONArray arr, String s) {
        for (int i = 0; i < arr.length(); i++) {
            if (s.equals(arr.optString(i))) return true;
        }
        return false;
    }
}
