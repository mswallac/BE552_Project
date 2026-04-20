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
        List<String[]> partLabels = new ArrayList<>();

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
                partLabels.add(new String[]{partId, role});
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

        StringBuilder partList = new StringBuilder();
        for (String[] pl : partLabels) {
            partList.append("&nbsp;&nbsp;").append(pl[0]).append(" <i>(").append(pl[1]).append(")</i><br>");
        }

        return "Imported design space <b>" + safeSpaceId + "</b><br>"
            + "GOLDBAR: <code>" + goldbar + "</code><br>"
            + tuExpressions.size() + " transcription unit(s), " + partLabels.size() + " part(s):<br>"
            + partList
            + "View the graph at <a href='http://localhost:8080' target='_blank'>http://localhost:8080</a>.";
    }

    @Tool(description =
        "Import a user-specified set of biological parts into Knox as a design space. Prefer this tool " +
        "over importCircuitAsGoldbar when you want to CHOOSE the parts yourself — MCPGeneBank's " +
        "build_from_template auto-selector sometimes picks composite/wrong-role parts. Recommended flow: " +
        "(1) call MCPGeneBank search_parts once per slot with an explicit part_type filter (e.g. " +
        "part_type=\"promoter\" for a sensor promoter, part_type=\"regulator\" for a TF, part_type=\"reporter\" " +
        "for GFP/RFP). (2) pick one or more IDs from each search result. (3) lay them out in 5'→3' order " +
        "as `id:role` pairs separated by commas; separate transcription units with `|`. " +
        "Roles should be one of: promoter, ribosome_entry_site, CDS, terminator, ribozyme, engineered_region. " +
        "Example for a mercury→RFP biosensor:\n" +
        "  tusSpec = \"BBa_K346001:promoter,BBa_B0034:ribosome_entry_site,BBa_K346002:CDS,BBa_B0015:terminator|BBa_R0040:promoter,BBa_B0034:ribosome_entry_site,BBa_E1010:CDS,BBa_B0015:terminator\"\n" +
        "Returns the new design space ID and a labelled parts list. View at http://localhost:8080.",
        returnDirect = true)
    String importPartsAsGoldbar(
            @ToolParam(description = "`|`-separated transcription units; each TU is a comma-separated list of `partId:role` pairs in 5'→3' order. See tool description for example.") String tusSpec,
            @ToolParam(description = "Name for the new Knox design space (e.g. 'mercury_biosensor_v2'). Alphanumeric + underscore/hyphen.") String spaceName) {

        System.out.println("\nAI: importPartsAsGoldbar spaceName='" + spaceName + "' tusSpec='" + tusSpec + "'");

        if (tusSpec == null || tusSpec.isBlank()) {
            return "Error: tusSpec is empty. Pass at least one TU like 'BBa_xxx:promoter,BBa_yyy:CDS'.";
        }
        if (spaceName == null || spaceName.isBlank()) {
            return "Error: spaceName is required.";
        }

        List<String> tuExpressions = new ArrayList<>();
        JSONObject categories = new JSONObject();
        List<String[]> partLabels = new ArrayList<>(); // [id, role] for the response message

        for (String tuChunk : tusSpec.split("\\|")) {
            tuChunk = tuChunk.trim();
            if (tuChunk.isEmpty()) continue;
            List<String> tuAtoms = new ArrayList<>();
            for (String token : tuChunk.split(",")) {
                token = token.trim();
                if (token.isEmpty()) continue;
                String partId;
                String role;
                int colon = token.indexOf(':');
                if (colon > 0) {
                    partId = token.substring(0, colon).trim();
                    role = mapTypeToRole(token.substring(colon + 1).trim());
                } else {
                    partId = token;
                    role = "engineered_region";
                }
                if (partId.isEmpty()) continue;

                String atom = safeAtom(partId);
                tuAtoms.add(atom);
                partLabels.add(new String[]{partId, role});

                if (!categories.has(atom)) categories.put(atom, new JSONObject());
                JSONObject atomEntry = categories.getJSONObject(atom);
                if (!atomEntry.has(role)) atomEntry.put(role, new JSONArray());
                JSONArray ids = atomEntry.getJSONArray(role);
                if (!containsString(ids, partId)) ids.put(partId);
            }
            if (!tuAtoms.isEmpty()) tuExpressions.add(String.join(" . ", tuAtoms));
        }

        if (tuExpressions.isEmpty()) {
            return "Error: no usable parts parsed from tusSpec='" + tusSpec + "'.";
        }

        String goldbar = tuExpressions.size() == 1
            ? tuExpressions.get(0)
            : "(" + String.join(") . (", tuExpressions) + ")";

        String safeSpaceId = safeAtom(spaceName);
        try {
            designSpaceService.importGoldbar(goldbar, categories, safeSpaceId,
                "", 1.0, false);
        } catch (Exception e) {
            return "Error importing GOLDBAR into Knox: " + e.getMessage()
                + "<br>GOLDBAR was: " + goldbar
                + "<br>Categories keys: " + categories.keySet();
        }

        StringBuilder partList = new StringBuilder();
        for (String[] pl : partLabels) {
            partList.append("&nbsp;&nbsp;").append(pl[0]).append(" <i>(").append(pl[1]).append(")</i><br>");
        }

        return "Imported design space <b>" + safeSpaceId + "</b><br>"
            + "GOLDBAR: <code>" + goldbar + "</code><br>"
            + tuExpressions.size() + " transcription unit(s), " + partLabels.size() + " part(s):<br>"
            + partList
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
