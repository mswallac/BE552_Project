package knox.spring.data.neo4j.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import knox.spring.data.neo4j.sample.DesignSampler.EnumerateType;
import knox.spring.data.neo4j.services.DesignSpaceService;

import java.util.*;
import java.util.function.Function;

/**
 * Tools for enumerating Knox designs and fetching their assembled DNA
 * sequences.
 *
 * Sequence flow is batched: collect every unique part ID across the N
 * enumerated designs, fire ONE MCP `get_parts_batch` call to MCPGeneBank,
 * then assemble each design's concatenated sequence from the prefetched
 * map. For a space with 30 unique parts across 100 designs this is one
 * MCP round-trip instead of 30 sequential get_part calls.
 */
public class SequenceTools {

    private final DesignSpaceService designSpaceService;
    // Calls MCP `get_parts_batch({part_ids: [...]})`, returns the raw JSON.
    // Supplied by AiController (which owns the MCP client). May be null
    // when MCPGeneBank is unreachable — sequence fetches then degrade
    // gracefully to "unavailable" annotations on each part.
    private final Function<List<String>, String> getPartsBatchMcp;
    private final ObjectMapper mapper = new ObjectMapper();

    public SequenceTools(DesignSpaceService designSpaceService,
                         Function<List<String>, String> getPartsBatchMcp) {
        this.designSpaceService = designSpaceService;
        this.getPartsBatchMcp = getPartsBatchMcp;
    }

    @Tool(description =
        "Enumerate up to N distinct designs from a Knox design space by walking " +
        "start→accept paths in the graph. Returns a JSON list, one entry per " +
        "design, each with its ordered list of part IDs and roles. Use this to " +
        "preview the combinatorial space before fetching sequences. Pair with " +
        "`getDesignSequences` to get the DNA for those same designs.",
        returnDirect = true)
    String enumerateDesigns(
            @ToolParam(description = "Knox design space ID, e.g. 'arsenic_biosensor_v1'") String spaceID,
            @ToolParam(description = "Max number of designs to enumerate (default 10, max 100).") int numDesigns) {

        System.out.println("\nAI: enumerateDesigns spaceID='" + spaceID + "' n=" + numDesigns);
        int n = (numDesigns <= 0) ? 10 : Math.min(numDesigns, 100);

        Collection<List<Map<String, Object>>> designs;
        try {
            designs = designSpaceService.enumerateDesignSpace(
                spaceID, n, 1, 0, 1, EnumerateType.DFS,
                true, true, false, false);
        } catch (Exception e) {
            return "Error enumerating design space '" + spaceID + "': " + e.getMessage();
        }

        ArrayNode arr = mapper.createArrayNode();
        int idx = 0;
        for (List<Map<String, Object>> design : designs) {
            ObjectNode designNode = mapper.createObjectNode();
            designNode.put("index", idx++);
            ArrayNode partsArr = mapper.createArrayNode();
            for (Map<String, Object> p : design) {
                if (isBlank(p)) continue;
                ObjectNode partNode = mapper.createObjectNode();
                partNode.put("part_id", Objects.toString(p.get("id"), ""));
                partNode.put("roles", Objects.toString(p.get("roles"), ""));
                partNode.put("orientation", Objects.toString(p.get("orientation"), ""));
                partsArr.add(partNode);
            }
            designNode.set("parts", partsArr);
            arr.add(designNode);
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("space_id", spaceID);
        out.put("design_count", arr.size());
        out.set("designs", arr);
        try {
            return "<pre>" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "</pre>";
        } catch (JsonProcessingException e) {
            return "Error formatting enumeration: " + e.getMessage();
        }
    }

    @Tool(description =
        "For a Knox design space, enumerate up to N designs AND fetch the " +
        "concatenated DNA sequence for each. Performs a SINGLE batch sequence " +
        "lookup (MCP get_parts_batch) covering every unique part ID across all " +
        "enumerated designs — no per-part round-trips. Returns a FASTA-style " +
        "text with per-design part breakdown. Flags UniProt entries whose " +
        "sequence is a protein (amino acid), not DNA — those are skipped in " +
        "the concat but still listed. Output is designed to feed into the Evo2 " +
        "/ generative-syn-bio scorer downstream.",
        returnDirect = true)
    String getDesignSequences(
            @ToolParam(description = "Knox design space ID") String spaceID,
            @ToolParam(description = "Number of designs to assemble sequences for (default 3, max 20).") int numDesigns) {

        System.out.println("\nAI: getDesignSequences spaceID='" + spaceID + "' n=" + numDesigns);
        int n = (numDesigns <= 0) ? 3 : Math.min(numDesigns, 20);

        Collection<List<Map<String, Object>>> designs;
        try {
            designs = designSpaceService.enumerateDesignSpace(
                spaceID, n, 1, 0, 1, EnumerateType.DFS,
                true, true, false, false);
        } catch (Exception e) {
            return "Error enumerating '" + spaceID + "': " + e.getMessage();
        }
        if (designs.isEmpty()) {
            return "No designs found in '" + spaceID + "'.";
        }

        // 1) Collect every unique part ID across all designs.
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (List<Map<String, Object>> design : designs) {
            for (Map<String, Object> p : design) {
                if (isBlank(p)) continue;
                String pid = Objects.toString(p.get("id"), "");
                if (!pid.isEmpty()) uniqueIds.add(pid);
            }
        }
        System.out.println("Unique parts across " + designs.size() + " designs: " + uniqueIds.size());

        // 2) ONE MCP round-trip for all sequences.
        Map<String, PartSeq> seqMap = batchFetch(new ArrayList<>(uniqueIds));

        // 3) Render FASTA for each design by map lookup.
        StringBuilder out = new StringBuilder();
        out.append("Assembled DNA sequences for ").append(designs.size())
           .append(" design(s) from <b>").append(spaceID).append("</b><br>")
           .append("(").append(uniqueIds.size()).append(" unique parts, 1 batch MCP call)<br><br>");

        int idx = 0;
        for (List<Map<String, Object>> design : designs) {
            StringBuilder seq = new StringBuilder();
            StringBuilder breakdown = new StringBuilder();
            boolean anyProtein = false;
            int slotIdx = 0;
            for (Map<String, Object> p : design) {
                if (isBlank(p)) continue;
                String partId = Objects.toString(p.get("id"), "");
                String role = Objects.toString(p.get("roles"), "");
                PartSeq ps = seqMap.getOrDefault(partId, new PartSeq("", false, "missing from DB"));
                if (ps.isProtein) anyProtein = true;
                if (!ps.isProtein) seq.append(ps.sequence);
                breakdown.append(String.format(
                    "&nbsp;&nbsp;%d. %s <i>(%s)</i> — %d %s%s<br>",
                    ++slotIdx,
                    partId,
                    role.isEmpty() ? "?" : role,
                    ps.sequence.length(),
                    ps.isProtein ? "aa (protein — skipped in concat)"
                                 : (ps.sequence.isEmpty() ? "" : "bp"),
                    ps.note.isEmpty() ? "" : " — " + ps.note));
            }
            out.append("<b>&gt;").append(spaceID).append(" design ").append(idx)
               .append(" | ").append(seq.length()).append(" bp")
               .append(anyProtein ? " (protein CDS parts omitted)" : "")
               .append("</b><br>");
            out.append("<code style=\"word-break:break-all;\">")
               .append(seq)
               .append("</code><br>")
               .append(breakdown)
               .append("<br>");
            idx++;
        }
        return out.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static boolean isBlank(Map<String, Object> part) {
        Object b = part.get("isBlank");
        if (b instanceof Boolean) return (Boolean) b;
        if (b instanceof String) return "true".equalsIgnoreCase((String) b);
        return false;
    }

    private record PartSeq(String sequence, boolean isProtein, String note) {}

    private Map<String, PartSeq> batchFetch(List<String> ids) {
        Map<String, PartSeq> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        if (getPartsBatchMcp == null) {
            for (String id : ids) out.put(id, new PartSeq("", false, "MCPGeneBank unreachable"));
            return out;
        }
        String raw;
        try {
            raw = getPartsBatchMcp.apply(ids);
        } catch (Exception e) {
            for (String id : ids) out.put(id, new PartSeq("", false, "batch fetch failed: " + e.getMessage()));
            return out;
        }
        if (raw == null || raw.isBlank()) {
            for (String id : ids) out.put(id, new PartSeq("", false, "empty batch response"));
            return out;
        }
        try {
            JsonNode node = mapper.readTree(raw);
            JsonNode results = node.path("results");
            if (results.isObject()) {
                Iterator<String> fields = results.fieldNames();
                while (fields.hasNext()) {
                    String pid = fields.next();
                    JsonNode part = results.get(pid);
                    String seq = part.path("sequence").asText("");
                    String source = part.path("source").asText("");
                    boolean isProtein = isProteinSeq(seq, source);
                    out.put(pid, new PartSeq(seq, isProtein, ""));
                }
            }
            for (String id : ids) {
                if (!out.containsKey(id)) {
                    out.put(id, new PartSeq("", false, "not found"));
                }
            }
        } catch (Exception e) {
            for (String id : ids) out.put(id, new PartSeq("", false, "parse error: " + e.getMessage()));
        }
        return out;
    }

    // Source metadata is authoritative — UniProt is our only protein source.
    // Fall back to a char-ratio check for any part without a source tag: if
    // >10% of non-whitespace chars are outside IUPAC DNA/RNA, call it protein.
    // This tolerates IUPAC ambiguity codes (R/Y/S/W/K/M/B/D/H/V/N) and gap
    // chars that the old strict whitelist falsely flagged.
    private static boolean isProteinSeq(String seq, String source) {
        if (seq == null || seq.isEmpty()) return false;
        if ("uniprot".equalsIgnoreCase(source)) return true;
        int total = 0;
        int nonDna = 0;
        for (int i = 0; i < seq.length(); i++) {
            char ch = Character.toUpperCase(seq.charAt(i));
            if (Character.isWhitespace(ch)) continue;
            total++;
            switch (ch) {
                case 'A': case 'C': case 'G': case 'T': case 'U':
                case 'R': case 'Y': case 'S': case 'W': case 'K':
                case 'M': case 'B': case 'D': case 'H': case 'V':
                case 'N': case '-': case '.':
                    break;
                default:
                    nonDna++;
            }
        }
        return total > 0 && (nonDna * 10) > total;
    }
}
