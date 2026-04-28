# BioPilot — Code

The BioPilot codebase is split into three integrated sub-projects, each
maintained in its own git repository (Knox as a regular folder, the
other two as git submodules pinned to specific commits). All three live
in this directory.

| Sub-project | What it does | Stack |
|-------------|--------------|-------|
| [`Knox_BE552/`](Knox_BE552/) | LLM agent (Gemini 2.5), GOLDBAR combinatorial DSL, Neo4j-backed design spaces, Sequence Viewer modal, Evo 2 fill REST endpoints, GenBank export. Serves the demo UI at `localhost:8080`. | Java 17, Spring Boot 3.5, Spring AI 1.1.1, Spring Data Neo4j 7, libSBOLj 2.4, Maven |
| [`MCPGeneBank/`](MCPGeneBank/) | Curated parts database (~750 parts: Cello UCFs + iGEM canonicals + iGEM-distribution kit + reviewed UniProt). Exposes `search_parts`, `search_parts_batch`, `get_part`, `get_parts_batch` over MCP/SSE. Vector search via Qdrant + sentence-transformers, with an exact-ID fast-path for accession lookups. | Python 3.11, FastMCP, Qdrant, sentence-transformers, httpx |
| [`generative-syn-bio/`](generative-syn-bio/) | Local Evo 2 pipeline (`evo2_1b_base` / `evo2_7b`), Cello UCF parser, multi-objective scorer (GC, MFE, Shine-Dalgarno, perplexity), `PartSpec` Pydantic schema. Optional component — the live demo uses NVIDIA's hosted Evo 2 NIM, not this local copy. | Python 3.11, PyTorch, ViennaRNA, evo2 |

## Install / build / run

The canonical install/run guide is the [top-level `README.md`](../../README.md)
two directories up. It covers:

- System dependencies (Docker, Java 17, Python 3.11, Anaconda)
- API keys (`GEMINI_API_KEY`, `NVIDIA_API_KEY`) placed in
  `Knox_BE552/knox-master/.env`
- The build sequence (`pip install`, `scrape_clean.py`,
  `docker-compose up --build`)
- Smoke-test commands and known-good demo prompts

## Code commenting

Every Knox / MCPGeneBank Java + Python source file has javadoc-style or
docstring headers explaining purpose. Notable annotated entry points:

- `Knox_BE552/knox-master/src/main/java/knox/spring/data/neo4j/controller/AiController.java`
  — LLM agent + system prompt + MCP tool resolution.
- `Knox_BE552/knox-master/src/main/java/knox/spring/data/neo4j/controller/SequenceViewController.java`
  — REST endpoints `/designs/sequences` and `/evo2/fill`.
- `Knox_BE552/knox-master/src/main/java/knox/spring/data/neo4j/services/Evo2Service.java`
  — NVIDIA Evo 2 NIM client.
- `Knox_BE552/knox-master/src/main/java/knox/spring/data/neo4j/ai/SequenceTools.java`
  — Per-design sequence assembly via batched MCP calls.
- `Knox_BE552/knox-master/src/main/resources/static/js/knox.js` (search
  for `// ─── Sequence Viewer`) — frontend slot map + Evo 2 regenerate
  + GenBank export.
- `MCPGeneBank/bio-circuit-ai/mcp_server.py` — FastMCP tool definitions.
- `MCPGeneBank/bio-circuit-ai/database/vector_store.py` — vector search
  with the exact-ID fast-path.
- `MCPGeneBank/bio-circuit-ai/ingestion/ingest_*.py` — per-source
  ingesters (Cello, iGEM canonical, iGEM distribution, UniProt).

## Source repositories

- Knox + parent repo: https://github.com/mswallac/BE552_Project
- MCPGeneBank submodule: pinned to a specific commit on
  https://github.com/mswallac/MCPGeneBank (per `.gitmodules` URL fork
  history)
- generative-syn-bio submodule: https://github.com/joshkome/generative-syn-bio

## Cloning fresh

```bash
git clone --recurse-submodules https://github.com/mswallac/BE552_Project.git
cd BE552_Project
# All code is at: Final Project BioPilot/Code/
```

If you cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```
