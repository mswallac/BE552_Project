# BioPilot — End-to-End Generative Design and Evolution of Synthetic Biology Designs

**BE552 / EC552 Final Project — Charles Van Hook, Joshua Kome, Michael Wallace**

BioPilot is an integrated platform that takes a natural-language prompt
("design an arsenic biosensor that produces GFP") and produces an
enumerable, sequence-resolved combinatorial design space, with optional
generative-fill of individual slots via NVIDIA's hosted Evo 2 40B foundation
model.

## Architecture

```
User prompt
   │
   ▼
┌──────────────────────────────┐
│  Knox (Spring Boot + Neo4j)  │
│  - LLM agent (Gemini 2.5)    │
│  - GOLDBAR combinatorial DSL │
│  - Sequence Viewer UI         │
└──┬──────────────┬─────────────┘
   │              │
   │  MCP / SSE   │ HTTPS
   ▼              ▼
┌──────────────────┐  ┌─────────────────────┐
│  MCPGeneBank     │  │  NVIDIA NIM Evo 2   │
│  (FastMCP, Qdrant)│ │  (hosted REST)      │
│  ~750 vetted parts│ │  Generative fill    │
│  Cello + iGEM +   │ │  of single slots    │
│  iGEM-distribution│ │  via 5' context     │
│  + UniProt        │ │                     │
└──────────────────┘  └─────────────────────┘
   │
   ▼
┌──────────────────────────────┐
│  generative-syn-bio          │
│  Cello UCF parsing,          │
│  Evo2 local generation,      │
│  multi-objective scoring     │
└──────────────────────────────┘
```

## Sub-projects

| Path | Description | Stack |
|------|-------------|-------|
| [`Final Project BioPilot/Code/Knox_BE552/`](Final%20Project%20BioPilot/Code/Knox_BE552/) | LLM agent, GOLDBAR design space, Sequence Viewer UI, Evo 2 fill REST endpoints | Java 17, Spring Boot 3.5, Spring AI 1.1.1, Spring Data Neo4j 7, libSBOLj 2.4, Maven |
| [`Final Project BioPilot/Code/MCPGeneBank/`](Final%20Project%20BioPilot/Code/MCPGeneBank/) | Curated parts database, MCP server (Cello + iGEM canonicals + iGEM-distribution kit + UniProt) | Python 3.11, FastMCP, Qdrant, sentence-transformers, httpx |
| [`Final Project BioPilot/Code/generative-syn-bio/`](Final%20Project%20BioPilot/Code/generative-syn-bio/) | Local Evo 2 pipeline, scorer, Cello UCF parser | Python 3.11, PyTorch, ViennaRNA, evo2 (`evo2_1b_base`/`evo2_7b`) |

The deliverable folder `Final Project BioPilot/` contains the project
report and a pointer back to this code; see the [grading note](#be552-deliverable-layout)
at the bottom of this README.

## Dependencies

### Required for the live demo (Knox + MCPGeneBank)

- **Docker Desktop** ≥ 4.0 (with Docker Compose v2)
- **Anaconda / Python** ≥ 3.11 on the host (the MCP server runs on the host
  so Knox in a container can reach it via `host.docker.internal`)
- **Java JDK 17** — only needed if you want to run Knox without Docker;
  the `docker-compose up --build` path bakes a JDK into the container
- **Maven 3.9+** — same caveat as the JDK

### API keys

| Environment variable | Required for | Where to get |
|---------------------|--------------|--------------|
| `GEMINI_API_KEY` | Knox LLM agent | https://ai.google.dev/ (free tier OK) |
| `NVIDIA_API_KEY` | Sequence-viewer Evo 2 fill | https://build.nvidia.com/arc/evo2-40b (free developer tier) |
| `OPENAI_API_KEY` | (optional) Alternate LLM provider | https://platform.openai.com |
| `CLAUDE_API_KEY` | (optional) Alternate LLM provider | https://console.anthropic.com |

Place these in `Final Project BioPilot/Code/Knox_BE552/knox-master/.env` (gitignored). Example:

```
GEMINI_API_KEY=AIza...
NVIDIA_API_KEY=nvapi-...
```

### Optional for `generative-syn-bio` local Evo 2

- CUDA-capable GPU + NVIDIA drivers (for `evo2_7b`); the `evo2_1b_base`
  variant runs on CPU but slowly
- `HF_TOKEN` from https://huggingface.co/ (Evo 2 weights are gated)
- NUPACK 4.0 + RBS Calculator (separate installs, not pip-installable)

## Quick Start (full demo stack)

```bash
# 1. Clone with submodules
git clone --recurse-submodules https://github.com/mswallac/BE552_Project.git
cd BE552_Project

# 2. (One-time) Install MCPGeneBank Python deps
cd "Final Project BioPilot/Code/MCPGeneBank/bio-circuit-ai"
pip install -r requirements.txt
cd ../..

# 3. (One-time) Build the parts database
#    Pulls Cello UCFs, iGEM canonicals, iGEM-distribution kit, UniProt
cd "Final Project BioPilot/Code/MCPGeneBank/bio-circuit-ai"
python scrape_clean.py   # ~5-10 min, populates data/qdrant_store/
cd ../..

# 4. Set up API keys (see above) in Final Project BioPilot/Code/Knox_BE552/knox-master/.env

# 5. Terminal 1 — start MCP server on the HOST (not containerized)
python scripts/run_mcpgenebank_mcp.py
# Listens on http://127.0.0.1:8000/sse

# 6. Terminal 2 — start Knox + Neo4j
cd "Final Project BioPilot/Code/Knox_BE552/knox-master"
docker-compose up --build
# Knox UI:  http://localhost:8080
# Neo4j:    http://localhost:7474

# 7. Open the UI and try a design prompt:
#    "design an arsenic biosensor that produces GFP"
```

## Running individual components

### Knox alone (no LLM, no MCP)

```bash
cd "Final Project BioPilot/Code/Knox_BE552/knox-master"
docker-compose up --build      # Docker (preferred)
# or
mvn clean install
mvn spring-boot:run            # Requires local Neo4j on :7687
```

### MCPGeneBank quick demo

```bash
cd "Final Project BioPilot/Code/MCPGeneBank/bio-circuit-ai"
python demo.py                 # In-memory, no external services
```

### generative-syn-bio tests

```bash
cd "Final Project BioPilot/Code/generative-syn-bio"
pip install -e ".[dev]"
pytest tests/ -k "not integration" -v   # 104 tests, no GPU required
```

## Verifying the install

A working install should pass these smoke tests:

```bash
# MCP server returns 200 on its SSE endpoint
curl -s -o /dev/null -w "MCP=%{http_code}\n" http://localhost:8000/sse

# Knox returns 200
curl -s -o /dev/null -w "Knox=%{http_code}\n" http://localhost:8080/

# End-to-end agent call
curl -s -X POST 'http://localhost:8080/agent?prompt=design+an+arsenic+biosensor+that+produces+GFP&includeCost=false' --max-time 180
# Expect HTML response containing "Imported design space arsenic_biosensor..." and a list of 8 slots
```

## Demo prompts that reliably work

- `design an arsenic biosensor that produces GFP`
- `design an arsenic-sensing NOT gate that produces RFP`
- `make me a blue-green oscillator repressilator`
- `design a genetic toggle switch with LacI and TetR, one state glows red the other green`
- `design a circuit where arabinose induces GFP expression`
- `make five constitutive promoter variants driving GFP at different strengths`

After a design space is created, click **Enumerate Designs → Fetch Sequences →
View** on any design to open the Sequence Viewer; per-slot **Regenerate with
Evo 2** swaps the iGEM-curated DNA for an Evo 2-proposed alternative
generated from the upstream 5' context.

## Pulling collaborator updates

```bash
cd "Final Project BioPilot/Code/MCPGeneBank" && git pull origin main && cd ..
cd "Final Project BioPilot/Code/generative-syn-bio" && git pull origin main && cd ..
git add MCPGeneBank generative-syn-bio
git commit -m "bump submodules"
```

## Known gotchas

- Knox Neo4j credentials are hardcoded: `neo4j` / `kn0xkn0x`
- Spring AI's MCP client caches the SSE session; if you restart the host
  MCP server you must `docker-compose restart backend` so Knox refreshes
  its session URL
- The `evo2` pip package expects short model names (`evo2_1b_base`, not
  the full HuggingFace path)
- `data/raw/` and `data/qdrant_store/` are gitignored — run
  `scrape_clean.py` after cloning

## BE552 deliverable layout

Per the EC552 final-project guidelines, the graded artifacts live under
[`Final Project BioPilot/`](Final%20Project%20BioPilot/):

- `Final Project BioPilot/Code/` — index pointing to the actual
  sub-project code at the repo root (Knox, MCPGeneBank,
  generative-syn-bio). This README has the install/run instructions.
- `Final Project BioPilot/BioPilot Final Project Report.pdf` — 2-3 page
  summary of the project, components, and any compile/run notes.
