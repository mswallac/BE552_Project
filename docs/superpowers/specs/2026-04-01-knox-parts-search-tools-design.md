# Knox PartsSearchTools — MCPGeneBank Bridge for Knox LLM

## Summary

A new Spring AI tool class (`PartsSearchTools.java`) in Knox that gives the Knox LLM direct access to MCPGeneBank's biological parts database via HTTP calls to its FastAPI endpoints. This lets the LLM search for parts, find sensors/reporters/regulators, and import search results into Knox design spaces — all within the existing AI chat conversation.

## Requirements

- Knox's LLM must be able to search MCPGeneBank's parts database by natural language, by part type, and by functional role (sensor, reporter, regulator).
- Search results must include part IDs, names, types, organisms, functions, tags, and relevance scores so the LLM can judge match quality.
- The LLM must be able to import search results directly into Knox design spaces as role-grouped parts, ready for GOLDBAR rules.
- MCPGeneBank's FastAPI server must be running (default `http://localhost:8000`). The URL is configurable via `MCPGENEBANK_URL` environment variable.
- No fallback logic needed in the tools — the LLM handles empty/weak results with its own judgment (broaden search, skip slot, substitute, or tell user).

## Architecture

```
Knox AI Chat (Spring AI + OpenAI/Anthropic)
  │
  ├─ DesignTools      (existing — Knox graph operations)
  ├─ GoldbarTools     (existing — constraint generation)
  ├─ OperatorTools    (existing — AND/OR/JOIN/MERGE)
  ├─ GroupTools       (existing — group management)
  └─ PartsSearchTools (NEW — HTTP calls to MCPGeneBank)
       │
       ▼
  MCPGeneBank FastAPI (http://localhost:8000)
    GET /search
    GET /search/sensors
    GET /search/reporters
    GET /search/regulators
    GET /parts/count
    GET /health
```

`PartsSearchTools` is a Spring `@Component` class with methods annotated for Spring AI function calling. Each method makes an HTTP GET/POST to MCPGeneBank's FastAPI, formats the response as a string the LLM can read, and returns it.

## Tools

### `searchParts(query, partType, limit)`
- Wraps: `GET /search?q={query}&part_type={partType}&limit={limit}`
- Returns: JSON list of parts with part_id, name, type, organism, function, tags, relevance_score
- The LLM uses this for general-purpose part discovery

### `searchSensors(target, limit)`
- Wraps: `GET /search/sensors?target={target}&limit={limit}`
- Returns: sensor/promoter parts responsive to the target molecule
- Example: `searchSensors("arsenic")` → Pars promoter, etc.

### `searchReporters(signal, limit)`
- Wraps: `GET /search/reporters?signal={signal}&limit={limit}`
- Returns: reporter parts producing the desired output signal
- Example: `searchReporters("green fluorescence")` → GFP, sfGFP, etc.

### `searchRegulators(target, limit)`
- Wraps: `GET /search/regulators?target={target}&limit={limit}`
- Returns: transcription factors and regulators for a target
- Example: `searchRegulators("arsenic")` → ArsR, etc.

### `getPartsCount()`
- Wraps: `GET /parts/count`
- Returns: total number of parts in the database
- Useful for the LLM to know what's available

### `importPartsAsDesignSpace(query, partType, spacePrefix)`
- Wraps: `GET /search?q={query}&part_type={partType}&limit=20` → formats as Knox CSV → `POST /import/csv`
- This is a compound operation: search MCPGeneBank, then create a Knox design space from the results
- Groups parts by their Knox role (promoter, cds, ribosomeBindingSite, terminator)
- Returns: the design space ID created and how many parts were imported
- The LLM calls this when it wants to pull parts into Knox for GOLDBAR operations

## Configuration

### Environment variable

`MCPGENEBANK_URL` — base URL for MCPGeneBank's FastAPI server.
- Default: `http://localhost:8000`
- In Docker: `http://host.docker.internal:8000` (host machine from container)

### Spring Boot config

Add to `application.properties` (or use env var):
```properties
mcpgenebank.url=${MCPGENEBANK_URL:http://localhost:8000}
```

### Docker Compose change

Add to `docker-compose.yml` backend environment:
```yaml
MCPGENEBANK_URL: ${MCPGENEBANK_URL:-http://host.docker.internal:8000}
```

## Implementation Details

### HTTP client

Use Spring's `RestClient` (available in Spring Boot 3.5) for synchronous HTTP calls. No need for async — the LLM waits for tool results anyway.

### Response formatting

Each tool returns a plain string (not JSON object) because Spring AI function calling expects string returns for the LLM to consume. Format results as readable text with structured data:

```
Found 3 sensors for "arsenic":

1. BBa_K1031907 — Pars Arsenic Sensing Promoter
   Type: promoter | Organism: E. coli | Source: igem
   Function: Promoter responsive to arsenite ions
   Tags: arsenic, metal sensing, biosensor

2. BBa_J45992 — PcopA Copper Responsive Promoter
   Type: promoter | Organism: E. coli | Source: igem
   Function: Copper-responsive promoter
   Tags: copper, metal sensing, biosensor

(3 results from 1,200 parts in database)
```

This format lets the LLM reason about the results and make decisions (e.g., "BBa_J45992 is copper-sensing, not arsenic — I'll skip it").

### Error handling

- If MCPGeneBank is unreachable: return "Parts database is not available. Make sure MCPGeneBank is running at {url}. Start it with: cd MCPGeneBank/bio-circuit-ai && uvicorn api.main:app --reload"
- If search returns empty: return "No parts found for '{query}'. Try a broader search term."
- If search returns results with low relevance: include the relevance scores so the LLM can decide

### `importPartsAsDesignSpace` implementation

This tool reuses the CSV generation logic from `sync_parts_to_knox.py` but implemented in Java:
1. Call `GET /search?q={query}&part_type={partType}&limit=20`
2. Parse JSON response into a list of parts
3. For each part, build a componentID string: `{part_id}__{name}__{tags}` (same encoding as sync_parts_to_knox.py)
4. Map part types to Knox roles (promoter→promoter, reporter/regulator/coding→cds, rbs→ribosomeBindingSite, terminator→terminator)
5. Build CSV strings in memory (components CSV + designs CSV)
6. Call `DesignSpaceService.importCSV()` directly (same service, no HTTP round-trip)
7. Return the space ID and part count

### Registration in AiController

Add `PartsSearchTools` to the existing tool list in `AiController.java`:

```java
// Current:
ChatResponse response = chatClient.prompt()
    .tools(new GroupTools(...), new DesignTools(...), new OperatorTools(...), new GoldbarTools(...))
    .user(prompt)
    .call()
    .chatResponse();

// After:
ChatResponse response = chatClient.prompt()
    .tools(new GroupTools(...), new DesignTools(...), new OperatorTools(...), new GoldbarTools(...), new PartsSearchTools(...))
    .user(prompt)
    .call()
    .chatResponse();
```

## Files Changed

| File | Change |
|------|--------|
| Create: `src/main/java/knox/spring/data/neo4j/ai/PartsSearchTools.java` | New tool class with 6 methods |
| Modify: `src/main/java/knox/spring/data/neo4j/controller/AiController.java` | Add PartsSearchTools to tool list |
| Modify: `docker-compose.yml` | Add MCPGENEBANK_URL env var |
| Modify: `src/main/resources/application.properties` | Add mcpgenebank.url config (create if doesn't exist) |

## Testing

- Unit test `PartsSearchTools` with mocked HTTP responses
- Integration test: start both Knox (Docker) and MCPGeneBank (uvicorn), use Knox AI chat to ask "find me arsenic sensing promoters" and verify the LLM calls `searchSensors` and returns real parts
- Test `importPartsAsDesignSpace` by verifying a new design space appears in Knox after the call

## What This Does NOT Do

- Does not replace MCPGeneBank's own circuit assembly pipeline — Knox handles design space construction
- Does not add Evo 2 scoring — that's a separate integration
- Does not modify MCPGeneBank's code — it's a pure consumer of existing API endpoints
