# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BE552 course project workspace containing three integrated sub-projects for AI-driven synthetic biology circuit design:

1. **Knox** (`Knox_BE552/knox-master/`) — Java/Spring Boot genetic design space repository backed by Neo4j graph database. Provides GOLDBAR combinatorial design framework, SBOL import/export, and AI chat integration via Spring AI (OpenAI + Anthropic + Google Gemini).
2. **MCPGeneBank** (`MCPGeneBank/bio-circuit-ai/`) — Python natural-language-to-genetic-circuit pipeline. Takes plain English ("detect arsenic and glow green") and assembles real circuits from 1,200+ biological parts using semantic vector search (Qdrant) and LLM orchestration (GPT-4o). Also exposes tools via MCP server.
3. **generative-syn-bio** (`generative-syn-bio/`) — Python pipeline connecting Cello circuit design outputs to the Evo 2 DNA language model for context-aware sequence generation. Compares conditioning modes (no context / upstream only / full context / tagged full) as an ablation study.

## Build & Run Commands

### Knox (Java 17 / Maven / Spring Boot)
```bash
cd Knox_BE552/knox-master

# Docker (preferred) — starts Neo4j + Spring Boot backend
docker-compose up --build
# Web UI: http://localhost:8080  |  Neo4j browser: http://localhost:7474

# Without Docker
mvn clean install
mvn spring-boot:run
# Requires local Neo4j instance (user: neo4j, password: kn0xkn0x)
```

### Full demo stack (Knox + MCPGeneBank MCP bridge)
For the end-to-end "design me a biosensor" flow, MCPGeneBank's MCP server must be
running on the **host** (not containerized — easier to iterate). Knox's container
reaches it via `host.docker.internal:8080`.
```bash
# Terminal 1 — MCPGeneBank MCP server on host (FastMCP defaults to :8000)
cd /c/Users/black/Documents/BE552_Project
/c/Users/black/anaconda3/python.exe scripts/run_mcpgenebank_mcp.py
# listens on http://127.0.0.1:8000/sse

# Terminal 2 — Knox + Neo4j via docker
cd Knox_BE552/knox-master
GEMINI_API_KEY=... docker-compose up --build

# Then ask Knox's /agent endpoint something like
#   "design me an arsenic biosensor that produces GFP"
# Gemini will call MCPGeneBank tools (remote MCP) + Knox CircuitImportTools
# and create a GOLDBAR-backed design space viewable at localhost:8080.
```
Override `MCPGENEBANK_URL` if the MCP server is elsewhere (it defaults to
`http://host.docker.internal:8000`). Knox requires MCPGeneBank to be running for
parts queries — there is no offline sidecar fallback; the ~7k-part Qdrant store
in MCPGeneBank is the sole source of truth.

### MCPGeneBank (Python 3.11+ / FastAPI)
```bash
cd MCPGeneBank/bio-circuit-ai
pip install -r requirements.txt
cp .env.example .env   # then fill in OPENAI_API_KEY, NCBI_EMAIL

# Quick demo (no external services needed — seeds in-memory vector store)
python demo.py

# Full stack
docker run -p 6333:6333 qdrant/qdrant   # start Qdrant
python run_ingestion.py                   # ingest biological parts
uvicorn api.main:app --reload             # API at http://localhost:8000

# MCP server (for Claude Desktop / Cursor integration)
python mcp_server.py          # stdio transport
python mcp_server.py --sse    # SSE transport on port 8000 (FastMCP default)

# Ingestion must be run before MCP server (or use scrape_300.py for quick seed)
```

### generative-syn-bio (Python 3.11 / pytest)
```bash
cd generative-syn-bio
pip install -e ".[dev]"

# Tests
pytest tests/ -v                          # all tests (104 passing)
pytest tests/test_schema.py -v            # single stage
pytest tests/ -k "not integration" -v     # skip GPU-requiring tests
pytest tests/ -m integration -v           # GPU integration tests only

# Pipeline (requires GPU + HF_TOKEN in .env)
python scripts/run_pipeline.py --circuit data/processed/not_gate --mode TAGGED_FULL --n-candidates 100 --output results/not_gate_run1
python scripts/run_ablation.py --dataset data/reference/mutalik_rbs.csv --output results/ablation
```

### Syncing Parts: MCPGeneBank → Knox
```bash
# Dry run with demo data (no Qdrant or Knox needed)
python scripts/sync_parts_to_knox.py --query "arsenic" "GFP" --demo-seed --dry-run

# Upload to Knox (requires Knox running at localhost:8080)
python scripts/sync_parts_to_knox.py --query "arsenic" "GFP" "tetracycline" --or-mode --demo-seed

# With live Qdrant vector store (after running scrape_300.py)
python scripts/sync_parts_to_knox.py --query "arsenic biosensor" --limit 50 --or-mode
```

## Architecture

### How the three projects relate
- **Knox** manages design spaces as directed graphs in Neo4j and handles SBOL/GOLDBAR specifications
- **MCPGeneBank** provides the parts database and NL-to-circuit assembly pipeline; its MCP server can be consumed by any MCP-compatible client
- **generative-syn-bio** generates novel DNA sequences via Evo 2 for parts that don't exist in databases, using flanking context from Cello circuit designs

### MCPGeneBank data flow
```
User prompt → LLM Planner (GPT-4o) → structured params
  → Semantic search (Qdrant + sentence-transformers) → candidate BioParts
  → CircuitSpec (graph of FunctionalNodes + CircuitEdges)
  → assemble() fills each node with best-matching real part
  → CircuitDesign with TranscriptionUnits + concatenated sequence
```
- All parts normalized to `BioPart` schema (`models/part.py`) regardless of source (iGEM, GenBank, UniProt, Addgene)
- `CircuitSpec` is the universal input to the assembly engine — supports 8 built-in templates (biosensor, toggle_switch, repressilator, logic_not, logic_and, kill_switch, metabolic_pathway, cascade) plus custom topologies
- Vector store has 3-tier fallback: remote Qdrant → local on-disk → in-memory
- Rule-based fallback parser handles requests when LLM API is unavailable

### generative-syn-bio pipeline
```
parse_ucf() → Evo2Generator.generate() → MultiObjectiveScorer.rank() → CircuitValidator.validate_circuit()
```
- `PartSpec` (Pydantic model in `src/schema/part_spec.py`) is the central data contract across all stages
- Evo 2 model is lazy-loaded; uses `evo2_1b_base` for dev (Mac CPU), `evo2_7b` for GPU runs
- ViennaRNA Python bindings import as `RNA` (from `ViennaRNA` pip package)
- `pythonpath = ["."]` in pyproject.toml is required for pytest to resolve `src.*` imports

### Knox architecture
- Spring Boot 3.5 + Spring Data Neo4j 7 + Spring AI 1.1.1 (OpenAI + Anthropic + Google Gemini)
- Domain model: DesignSpace → Node → Edge → Component, with Branch/Commit/Snapshot for versioning
- GOLDBAR parser (`goldbar/`) converts combinatorial specs into design space graphs
- AI tools (`ai/`) expose design, GOLDBAR, group, and operator operations as Spring AI function calls
- SBOL import/export via libSBOLj 2.4

## Environment Variables

| Variable | Project | Purpose |
|----------|---------|---------|
| `OPENAI_API_KEY` | Knox, MCPGeneBank | OpenAI API for LLM features |
| `CLAUDE_API_KEY` | Knox | Anthropic API for AI chat |
| `GEMINI_API_KEY` | Knox | Google AI Studio key for Gemini chat (default provider) |
| `HF_TOKEN` | generative-syn-bio | Hugging Face token for Evo 2 model download |
| `EVO2_MODEL` | generative-syn-bio | Model ID override (default: evo2_1b_base) |
| `QDRANT_URL` | MCPGeneBank | Qdrant server (default: http://localhost:6333) |
| `NCBI_EMAIL` | MCPGeneBank | Required for NCBI Entrez API calls |

## Known Gotchas
- Knox Neo4j credentials are hardcoded: `neo4j` / `kn0xkn0x`
- evo2 package expects short model names like `evo2_1b_base`, not full HF paths
- MCPGeneBank working directory must be `bio-circuit-ai/` for relative imports to work
- `data/raw/` in generative-syn-bio is gitignored — run download scripts after cloning
- NUPACK 4.0 and RBS Calculator require separate installs (not pip-installable)
