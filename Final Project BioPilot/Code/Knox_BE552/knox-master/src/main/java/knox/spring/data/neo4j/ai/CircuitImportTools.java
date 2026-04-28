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
                // MCPGeneBank's CircuitDesign JSON emits the SBOL-style role as
                // "role" per part (e.g. "promoter", "cds", "terminator"); "type"
                // is not in the summary. Fall back to "type" defensively.
                String partType = textOrBlank(part, "role");
                if (partType.isBlank()) partType = textOrBlank(part, "type");
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
        "Import a user-specified genetic circuit into Knox as a COMBINATORIAL design space — a single slot " +
        "can hold multiple candidate part IDs, and Knox enumerates the cross-product of choices as valid " +
        "designs. Prefer this tool over importCircuitAsGoldbar when you want multiple designs or want to " +
        "choose parts yourself. Recommended flow: " +
        "(1) for each slot, call MCPGeneBank search_parts once with an explicit part_type filter (e.g. " +
        "part_type=\"promoter\", part_type=\"regulator\", part_type=\"reporter\"); pick the top 2-3 legit " +
        "candidates. (2) lay them out in 5'→3' order as `id1+id2+...:role[:label]` slot tokens separated by " +
        "commas; separate transcription units with `|`. Use `+` within a slot to add alternatives (a slot " +
        "with N alternatives contributes a factor of N to the design count). Give each functional slot " +
        "(promoter, reporter CDS, regulator CDS) ≥2 alternatives when possible so the space has >1 design.\n" +
        "\n" +
        "OPTIONAL third colon field: a short human-readable LABEL identifying the slot's biological " +
        "purpose (e.g. `constitutive_promoter`, `arsenic_sensor`, `ArsR_regulator`, `GFP_reporter`). " +
        "This label appears in the response so the user can tell at a glance what each slot does. " +
        "ALWAYS PROVIDE LABELS for non-obvious slots (sensor vs constitutive promoter, regulator vs " +
        "reporter CDS).\n" +
        "\n" +
        "Roles: promoter, ribosome_entry_site, CDS, terminator, ribozyme, engineered_region.\n" +
        "\n" +
        "Example — arsenic→GFP biosensor, 3 constitutive promoters × 2 GFP reporters (use BBa_K4767001 " +
        "Pars for the arsenic-responsive promoter, NOT the legacy BBa_K1031907 which is defunct):\n" +
        "  tusSpec = \"BBa_J23100+BBa_J23101+BBa_R0040:promoter:constitutive,BBa_B0034:ribosome_entry_site,BBa_K1031311:CDS:ArsR_regulator,BBa_B0015:terminator|BBa_K4767001:promoter:arsenic_sensor,BBa_B0034:ribosome_entry_site,BBa_E0040+BBa_K4930007:CDS:GFP_reporter,BBa_B0015:terminator\"\n" +
        "  → 3×2 = 6 enumerable designs.\n" +
        "\n" +
        "Also pass a `legend` string: a 1-3 line plain-English summary of the design (mechanism, what each " +
        "TU does, role of each picked part). This appears verbatim in the response so the user sees a " +
        "biology explanation alongside the part IDs. Example legend: \"Classic arsenic biosensor. TU1 " +
        "constitutively expresses ArsR (Anderson promoters → BBa_K1031311 ArsR CDS). TU2 is the sensing " +
        "output (BBa_K4767001 Pars arsenic-responsive promoter → GFP variants BBa_E0040/BBa_K4930007). " +
        "ArsR represses Pars; arsenic binds ArsR → release → GFP expression.\"\n" +
        "\n" +
        "Returns the design space ID, GOLDBAR, legend, labelled slot breakdown, design count, and UI link.",
        returnDirect = true)
    String importPartsAsGoldbar(
            @ToolParam(description = "`|`-separated transcription units; each TU is a comma-separated list of `id1[+id2+id3]:role[:label]` slot tokens in 5'→3' order. Use `+` for alternatives, optional 3rd colon field for a human label. See tool description for full example.") String tusSpec,
            @ToolParam(description = "Name for the new Knox design space (e.g. 'mercury_biosensor_v2'). Alphanumeric + underscore/hyphen.") String spaceName,
            @ToolParam(description = "Plain-English 1-3 line summary of the circuit mechanism and per-TU purpose. Appears verbatim in the response. Use to explain biology to the user. Pass empty string if no explanation is needed.") String legend) {

        System.out.println("\nAI: importPartsAsGoldbar spaceName='" + spaceName + "' tusSpec='" + tusSpec + "'");

        if (tusSpec == null || tusSpec.isBlank()) {
            return "Error: tusSpec is empty. Pass at least one TU like 'BBa_xxx:promoter,BBa_yyy:CDS'.";
        }
        if (spaceName == null || spaceName.isBlank()) {
            return "Error: spaceName is required.";
        }

        List<String> tuExpressions = new ArrayList<>();
        JSONObject categories = new JSONObject();
        // Each slot: {role, label, "id1, id2, id3"}
        List<String[]> slotLabels = new ArrayList<>();
        long designCount = 1;

        int tuIdx = 0;
        for (String tuChunk : tusSpec.split("\\|")) {
            tuChunk = tuChunk.trim();
            if (tuChunk.isEmpty()) continue;
            List<String> tuAtoms = new ArrayList<>();
            int slotIdx = 0;
            for (String token : tuChunk.split(",")) {
                token = token.trim();
                if (token.isEmpty()) continue;

                // Split on ':' into [parts, role, optional label]. Use split(":", -1)
                // variant keeps trailing empty strings but we don't want that; manual parse:
                String partsPart;
                String role;
                String label = "";
                String[] segs = token.split(":", 3);
                if (segs.length == 1) {
                    partsPart = segs[0].trim();
                    role = "engineered_region";
                } else if (segs.length == 2) {
                    partsPart = segs[0].trim();
                    role = mapTypeToRole(segs[1].trim());
                } else {
                    partsPart = segs[0].trim();
                    role = mapTypeToRole(segs[1].trim());
                    label = segs[2].trim();
                }
                if (partsPart.isEmpty()) continue;

                // Alternatives within a slot are separated by '+'. One atom per slot.
                List<String> altIds = new ArrayList<>();
                for (String alt : partsPart.split("\\+")) {
                    alt = alt.trim();
                    if (!alt.isEmpty()) altIds.add(alt);
                }
                if (altIds.isEmpty()) continue;

                String slotAtom = "s" + tuIdx + "_" + slotIdx + "_" + role;
                tuAtoms.add(slotAtom);
                slotLabels.add(new String[]{role, label, String.join(", ", altIds)});
                designCount *= altIds.size();

                JSONObject atomEntry = new JSONObject();
                JSONArray ids = new JSONArray();
                for (String id : altIds) ids.put(id);
                atomEntry.put(role, ids);
                categories.put(slotAtom, atomEntry);

                slotIdx++;
            }
            if (!tuAtoms.isEmpty()) {
                tuExpressions.add(String.join(" . ", tuAtoms));
                tuIdx++;
            }
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

        StringBuilder slotList = new StringBuilder();
        for (String[] sl : slotLabels) {
            String role = sl[0];
            String label = sl[1];
            String ids = sl[2];
            slotList.append("&nbsp;&nbsp;<i>(").append(role).append(")</i>");
            if (label != null && !label.isEmpty()) {
                slotList.append(" <b>").append(label).append("</b>");
            }
            slotList.append(" — ").append(ids).append("<br>");
        }

        String legendBlock = (legend != null && !legend.isBlank())
            ? "<br><i>" + legend.replace("\n", "<br>") + "</i><br>"
            : "<br>";

        return "Imported design space <b>" + safeSpaceId + "</b><br>"
            + "GOLDBAR: <code>" + goldbar + "</code>"
            + legendBlock
            + tuExpressions.size() + " transcription unit(s), " + slotLabels.size() + " slot(s), "
            + "<b>" + designCount + " enumerable design(s)</b>:<br>"
            + slotList
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
