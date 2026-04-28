# BioPilot — Code

The BioPilot codebase is organized as three integrated sub-projects, each
maintained in its own git repository and pulled in here as either a
top-level directory (Knox) or a git submodule (MCPGeneBank,
generative-syn-bio). To keep grading paths simple, the three sub-projects
live at the **repository root** rather than being copied into this folder.

| Component | Code location | Owner | What it does |
|-----------|---------------|-------|--------------|
| Knox + LLM agent + Sequence Viewer + Evo 2 fill | [`../../Knox_BE552/`](../../Knox_BE552/) | Mike Wallace | Java/Spring Boot service. Exposes `/agent` (LLM-driven design), `/designs/sequences`, `/evo2/fill`, plus a Sequence Viewer modal in the web UI. GOLDBAR combinatorial DSL, Neo4j-backed design spaces, MCP client, and the full demo orchestration logic live here. |
| MCPGeneBank — parts database + MCP server | [`../../MCPGeneBank/`](../../MCPGeneBank/) | Charles Van Hook | Python/FastMCP. Curated parts DB (~750 parts: Cello UCFs + iGEM canonicals + iGEM-distribution kit + UniProt). Vector search via Qdrant + sentence-transformers, with an exact-ID fast-path for accession lookups. Exposes `search_parts`, `search_parts_batch`, `get_part`, `get_parts_batch` over MCP/SSE. |
| generative-syn-bio — local Evo 2 + scorer | [`../../generative-syn-bio/`](../../generative-syn-bio/) | Joshua Kome | Python pipeline using the local `evo2_1b_base` / `evo2_7b` foundation models. Cello UCF parser, multi-objective scorer (GC, MFE, Shine-Dalgarno, perplexity), and the `PartSpec` Pydantic schema that flows between stages. |

## Install / build / run

All install, dependency, and run instructions live in the
[top-level README](../../README.md). That file is the canonical
reference for:

- System dependencies (Docker, Java 17, Python 3.11, Anaconda)
- API keys (`GEMINI_API_KEY`, `NVIDIA_API_KEY` — placed in
  `Knox_BE552/knox-master/.env`)
- The build sequence (`pip install`, `scrape_clean.py`,
  `docker-compose up --build`)
- Smoke-test commands and known-good demo prompts

## Why is the actual code one directory up?

The three sub-projects predate the BE552 deliverable folder and are
coupled to long-lived paths in CLAUDE.md, docker-compose.yml, the
MCPGeneBank submodule URL, and Python relative imports. Moving them
inside `Final Project BioPilot/Code/` would break those paths during the
final week. This README acts as the index that the EC552 grading rubric
expects — it lists every component and a clickable link to the code.

## Source-of-truth guarantees

- All code is committed to https://github.com/mswallac/BE552_Project
- Submodules pin specific commits in
  [`MCPGeneBank`](https://github.com/mswallac/MCPGeneBank) and
  [`generative-syn-bio`](https://github.com/mswallac/generative-syn-bio)
- `git status` at the project root should be clean for any released
  state; if a submodule shows `(modified content)`, run `git submodule
  update --init --recursive` to sync.
