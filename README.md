# Enabling End-to-End Generative Design and Evolution of Synthetic Biology Designs

**BE552 Project — Charles Van Hook, Joshua Kome, Michael Wallace**

An integrated platform that connects LLM-driven design orchestration, biological parts databases, and generative DNA language models for end-to-end synthetic biology circuit design.

## Architecture

```
User Prompt
     │
     ▼
┌──────────────┐
│  LLM Agent   │  Orchestrates the full pipeline
└──┬───┬───┬───┘
   │   │   │
   ▼   │   ▼
┌──────┐│┌──────────────┐
│ MCP  │││ Knox +       │
│ Gene ││▶ GOLDBAR      │  Combinatorial design rules
│ Bank │││ (Neo4j)      │  & design space enumeration
└──┬───┘│└──────┬───────┘
   │    │       │
   │    ▼       ▼
   │  ┌──────────────┐
   │  │   Evo 2      │  De novo sequence generation
   │  │  (Foundation  │  & likelihood-based scoring
   │  │   Model)      │
   │  └──────┬───────┘
   │         │
   ▼         ▼
┌──────────────────┐
│  Scored / Novel  │
│  Circuit Designs │
└──────────────────┘
```

## Sub-projects

| Directory | Owner | Description |
|-----------|-------|-------------|
| [`Knox_BE552/`](Knox_BE552/) | Mike | Knox genetic design space repository + GOLDBAR combinatorial framework. Java 17 / Spring Boot / Neo4j. |
| [`MCPGeneBank/`](MCPGeneBank/) | Charles | Bio-Circuit AI: NL-to-circuit pipeline with 1,200+ parts from iGEM, GenBank, UniProt, Addgene. Python / FastAPI / Qdrant. Exposes tools via MCP server. |
| [`generative-syn-bio/`](generative-syn-bio/) | Josh | Evo 2 integration for de novo part generation and log-probability scoring. Cello UCF parsing, multi-objective scoring, circuit validation. Python / PyTorch. |

## Quick Start

```bash
# Clone with submodules
git clone --recurse-submodules <this-repo-url>

# Or if already cloned:
git submodule update --init --recursive
```

See each sub-project's README for setup instructions, or [CLAUDE.md](CLAUDE.md) for a unified reference.

## Pulling Collaborator Updates

```bash
# Pull latest from a specific submodule
cd generative-syn-bio && git pull origin main && cd ..
cd MCPGeneBank && git pull origin main && cd ..

# Then commit the updated submodule pointer in the parent repo
git add generative-syn-bio MCPGeneBank
git commit -m "Update submodules to latest"
```
