package knox.spring.data.neo4j.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import knox.spring.data.neo4j.ai.SequenceTools;
import knox.spring.data.neo4j.services.DesignSpaceService;
import knox.spring.data.neo4j.services.Evo2Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * REST endpoints that power the Sequence Viewer UI (issue #26).
 *
 * GET /designs/sequences returns structured per-slot JSON for the viewer's
 * slot-map and highlighted-sequence rendering. POST /evo2/fill asks
 * NVIDIA's Evo 2 NIM to regenerate a single slot given the upstream 5'
 * context assembled from preceding slots in the same design.
 *
 * Both endpoints share SequenceTools (instantiated per-request because the
 * MCP tool-callback set is resolved lazily from Spring AI's MCP client).
 */
@RestController
public class SequenceViewController {

    private final DesignSpaceService designSpaceService;
    private final Evo2Service evo2Service;
    private final List<ToolCallbackProvider> mcpToolProviders;
    private final ObjectMapper mapper = new ObjectMapper();

    // Context truncation — Evo 2 accepts up to 131k tokens but at extremely
    // long context the latency / cost grow without meaningfully improving
    // the proposal for a short slot. 6k bp of 5' flank is plenty of local
    // context for RBS / CDS / terminator regeneration.
    private static final int MAX_SEED_BP = 6_000;

    public SequenceViewController(DesignSpaceService dss,
                                  Evo2Service evo2Service,
                                  @Autowired(required = false)
                                  List<ToolCallbackProvider> mcpToolProviders) {
        this.designSpaceService = dss;
        this.evo2Service = evo2Service;
        this.mcpToolProviders = mcpToolProviders == null ? List.of() : mcpToolProviders;
    }

    @GetMapping("/designs/sequences")
    public ResponseEntity<?> sequences(@RequestParam("spaceID") String spaceID,
                                       @RequestParam(value = "n", defaultValue = "3") int n) {
        try {
            List<SequenceTools.DesignView> views = newSequenceTools().enumerateWithSequences(spaceID, n);
            ObjectNode out = mapper.createObjectNode();
            out.put("space_id", spaceID);
            out.put("design_count", views.size());
            ArrayNode designs = out.putArray("designs");
            for (SequenceTools.DesignView dv : views) {
                ObjectNode dn = designs.addObject();
                dn.put("index", dv.index());
                dn.put("assembled_sequence", dv.assembledSequence());
                dn.put("assembled_length", dv.assembledSequence().length());
                dn.put("any_protein", dv.anyProtein());
                ArrayNode slots = dn.putArray("slots");
                for (SequenceTools.SlotView s : dv.slots()) {
                    ObjectNode sn = slots.addObject();
                    sn.put("index", s.index());
                    sn.put("part_id", s.partId());
                    sn.put("role", s.role());
                    sn.put("sequence", s.sequence());
                    sn.put("is_protein", s.isProtein());
                    sn.put("offset", s.offset());
                    sn.put("length", s.length());
                    sn.put("note", s.note());
                }
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/evo2/fill")
    public ResponseEntity<?> fill(@RequestBody Map<String, Object> req) {
        String spaceID = String.valueOf(req.getOrDefault("spaceID", ""));
        int designIdx = asInt(req.get("designIdx"), 0);
        int slotIdx = asInt(req.get("slotIdx"), 0);
        if (spaceID.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "spaceID required"));
        }

        try {
            List<SequenceTools.DesignView> views =
                newSequenceTools().enumerateWithSequences(spaceID, designIdx + 1);
            if (designIdx < 0 || designIdx >= views.size()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "designIdx " + designIdx + " out of range (have "
                        + views.size() + " designs)"));
            }
            SequenceTools.DesignView dv = views.get(designIdx);
            List<SequenceTools.SlotView> slots = dv.slots();
            if (slotIdx < 0 || slotIdx >= slots.size()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "slotIdx " + slotIdx + " out of range (have "
                        + slots.size() + " slots)"));
            }
            SequenceTools.SlotView target = slots.get(slotIdx);

            if (target.isProtein()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot regenerate a protein CDS slot — only DNA "
                           + "sequences are supported by Evo 2 forward fill."));
            }

            // Build 5' context from prior DNA slots. If any upstream slot is a
            // protein CDS we can't build coherent DNA context — refuse rather
            // than silently feed Evo 2 a broken seed.
            StringBuilder seed = new StringBuilder();
            for (int i = 0; i < slotIdx; i++) {
                SequenceTools.SlotView s = slots.get(i);
                if (s.isProtein()) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "Upstream slot " + i + " (" + s.partId()
                               + ") is a protein CDS — cannot build DNA 5' "
                               + "context. Regenerate disabled for this slot."));
                }
                seed.append(s.sequence());
            }
            String seedStr = seed.toString();
            if (seedStr.length() > MAX_SEED_BP) {
                seedStr = seedStr.substring(seedStr.length() - MAX_SEED_BP);
            }
            int numTokens = Math.max(target.length(), 1);

            Evo2Service.Evo2Result r = evo2Service.fillForward(seedStr, numTokens);

            ObjectNode resp = mapper.createObjectNode();
            resp.put("space_id", spaceID);
            resp.put("design_idx", designIdx);
            resp.put("slot_idx", slotIdx);
            resp.put("original_part_id", target.partId());
            resp.put("original_role", target.role());
            resp.put("original_sequence", target.sequence());
            resp.put("original_length", target.sequence().length());
            resp.put("seed_length", seedStr.length());
            resp.put("requested_tokens", numTokens);
            resp.put("proposed_sequence", r.generated());
            resp.put("proposed_length", r.generated().length());
            resp.put("mean_sampled_prob", r.meanSampledProb());
            resp.put("elapsed_ms", r.elapsedMs());
            if (r.error() != null && !r.error().isEmpty()) resp.put("error", r.error());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private SequenceTools newSequenceTools() {
        ToolCallback[] tools = mcpToolProviders.stream()
            .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
            .toArray(ToolCallback[]::new);
        Function<List<String>, String> fn = SequenceTools.resolveGetPartsBatch(tools);
        return new SequenceTools(designSpaceService, fn);
    }

    private static int asInt(Object v, int fallback) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (Exception ignored) {}
        }
        return fallback;
    }
}
