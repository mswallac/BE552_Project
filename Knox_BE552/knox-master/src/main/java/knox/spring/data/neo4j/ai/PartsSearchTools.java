package knox.spring.data.neo4j.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.InputStream;
import java.util.*;

public class PartsSearchTools {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PartEntry(
        String name,
        String type,
        String function,
        String description,
        String organism,
        String source,
        List<String> references,
        List<String> tags,
        Integer sequence_length
    ) {}

    private static final Map<String, PartEntry> PARTS = loadParts();

    private static Map<String, PartEntry> loadParts() {
        try (InputStream in = PartsSearchTools.class.getResourceAsStream("/parts_descriptions.json")) {
            if (in == null) {
                System.err.println("parts_descriptions.json not found on classpath");
                return Map.of();
            }
            return new ObjectMapper().readValue(in, new TypeReference<LinkedHashMap<String, PartEntry>>() {});
        } catch (Exception e) {
            System.err.println("Failed to load parts_descriptions.json: " + e);
            return Map.of();
        }
    }

    @Tool(description = "Search the curated parts metadata database by keyword. Matches substrings (case-insensitive) against part name, function, description, tags, and organism. Use this to answer free-text questions like 'what arsenic sensing promoters do you have' or 'show me GFP reporters' — these cannot be answered from the design-space graph alone.", returnDirect = true)
    String searchPartsByKeyword(
            @ToolParam(description = "Keyword to search for (e.g. 'arsenic', 'GFP', 'mercury')") String keyword,
            @ToolParam(description = "Optional part type filter: 'promoter', 'regulator', 'reporter', 'rbs', 'terminator', 'cds', or empty string for no filter.") String typeFilter,
            @ToolParam(description = "Max results to return. Recommended 10.") int limit) {
        System.out.println("\nAI: searchPartsByKeyword keyword='" + keyword + "' type='" + typeFilter + "' limit=" + limit);

        if (PARTS.isEmpty()) {
            return "Parts database is empty or failed to load.";
        }
        String kw = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT).trim();
        String tf = typeFilter == null ? "" : typeFilter.toLowerCase(Locale.ROOT).trim();
        int cap = limit <= 0 ? 10 : Math.min(limit, 50);

        List<Map.Entry<String, PartEntry>> hits = new ArrayList<>();
        for (var e : PARTS.entrySet()) {
            PartEntry p = e.getValue();
            if (!tf.isEmpty() && (p.type() == null || !p.type().toLowerCase(Locale.ROOT).contains(tf))) continue;
            if (!kw.isEmpty() && !matches(p, kw)) continue;
            hits.add(e);
            if (hits.size() >= cap) break;
        }

        if (hits.isEmpty()) {
            return "No parts matched keyword='" + keyword + "'"
                + (tf.isEmpty() ? "" : " type='" + typeFilter + "'") + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(hits.size()).append(" part(s) matching '").append(keyword).append("'");
        if (!tf.isEmpty()) sb.append(" [type=").append(typeFilter).append("]");
        sb.append(":<br><br>");
        for (var e : hits) {
            PartEntry p = e.getValue();
            sb.append("<b>").append(e.getKey()).append("</b> — ")
              .append(safe(p.name())).append(" (").append(safe(p.type())).append(")<br>")
              .append("&nbsp;&nbsp;").append(safe(p.function())).append("<br>");
            if (p.tags() != null && !p.tags().isEmpty()) {
                sb.append("&nbsp;&nbsp;tags: ").append(String.join(", ", p.tags())).append("<br>");
            }
            sb.append("<br>");
        }
        return sb.toString();
    }

    @Tool(description = "Get full metadata for a specific curated part by its ID (e.g. 'BBa_K1031907', 'P15905'). Returns name, type, function, description, organism, source, references, tags, and sequence length.", returnDirect = true)
    String getPartDetails(@ToolParam(description = "Part ID as it appears in the parts database") String partId) {
        System.out.println("\nAI: getPartDetails partId='" + partId + "'");
        if (partId == null || partId.isBlank()) {
            return "Please provide a part ID.";
        }
        PartEntry p = PARTS.get(partId.trim());
        if (p == null) {
            return "No curated metadata for part '" + partId + "'. It may exist in Neo4j as a design space but not in the curated sidecar.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(partId).append("</b><br>")
          .append("Name: ").append(safe(p.name())).append("<br>")
          .append("Type: ").append(safe(p.type())).append("<br>")
          .append("Function: ").append(safe(p.function())).append("<br>")
          .append("Description: ").append(safe(p.description())).append("<br>")
          .append("Organism: ").append(safe(p.organism())).append("<br>")
          .append("Source: ").append(safe(p.source())).append("<br>");
        if (p.tags() != null && !p.tags().isEmpty()) {
            sb.append("Tags: ").append(String.join(", ", p.tags())).append("<br>");
        }
        if (p.references() != null && !p.references().isEmpty()) {
            sb.append("References: ").append(String.join(", ", p.references())).append("<br>");
        }
        if (p.sequence_length() != null) {
            sb.append("Sequence length: ").append(p.sequence_length()).append(" bp<br>");
        }
        return sb.toString();
    }

    private static boolean matches(PartEntry p, String kw) {
        return contains(p.name(), kw)
            || contains(p.function(), kw)
            || contains(p.description(), kw)
            || contains(p.organism(), kw)
            || (p.tags() != null && p.tags().stream().anyMatch(t -> contains(t, kw)));
    }

    private static boolean contains(String s, String kw) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
