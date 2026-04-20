"""
Launcher for MCPGeneBank's MCP server that loosens host-header validation so
Knox (running inside docker) can reach it via `host.docker.internal:8000`.

FastMCP 1.27+ enables DNS-rebinding protection by default when bound to
localhost, and only allows Host headers matching 127.0.0.1:*, localhost:*,
[::1]:*. Requests from Knox's container arrive with Host: host.docker.internal
which gets rejected with "Invalid Host header" → the SSE handshake aborts with
"Request validation failed".

We disable the protection entirely for local development. Runs the MCP server
in SSE mode on the FastMCP default port (8000).

Usage:
    python scripts/run_mcpgenebank_mcp.py

Requires MCPGeneBank's requirements.txt installed in the active Python env
(mcp>=1.26, sentence-transformers, qdrant-client, etc.).
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Look first next to this script, then in any parent BE552_Project (useful when
# running from a git worktree — MCPGeneBank is a nested repo that only exists
# in the primary checkout, not in worktrees).
_CANDIDATES = [
    HERE.parent / "MCPGeneBank" / "bio-circuit-ai",
]
for anc in HERE.parents:
    if anc.name == "BE552_Project":
        _CANDIDATES.append(anc / "MCPGeneBank" / "bio-circuit-ai")
    _CANDIDATES.append(anc.parent / "BE552_Project" / "MCPGeneBank" / "bio-circuit-ai")

MCPGB_SRC = next((p for p in _CANDIDATES if p.exists()), None)
if MCPGB_SRC is None:
    raise SystemExit(
        "MCPGeneBank source not found. Tried:\n  "
        + "\n  ".join(str(p) for p in _CANDIDATES)
    )
sys.path.insert(0, str(MCPGB_SRC))

# Import the FastMCP instance constructed inside MCPGeneBank.
from mcp_server import mcp  # noqa: E402
from mcp.server.transport_security import TransportSecuritySettings  # noqa: E402

mcp.settings.transport_security = TransportSecuritySettings(
    enable_dns_rebinding_protection=False,
)

# Workaround: Spring AI's Google Gemini adapter (1.1.1) can't deserialize tool
# JSON schemas that contain `"default": null` — it throws a NullPointerException
# while building com.google.genai.types.Schema. Strip null defaults from every
# registered tool's parameter schema before starting the server.
def _strip_null_defaults(obj):
    if isinstance(obj, dict):
        if obj.get("default", "__NOT_SET__") is None:
            obj.pop("default", None)
        for v in obj.values():
            _strip_null_defaults(v)
    elif isinstance(obj, list):
        for item in obj:
            _strip_null_defaults(item)

_tools_attr = getattr(mcp._tool_manager, "_tools", {})
for tool in _tools_attr.values():
    if hasattr(tool, "parameters") and tool.parameters:
        _strip_null_defaults(tool.parameters)

# Filter composite parts out of the auto-selector candidate pool.
# MCPGeneBank's vector search over the ~7k parts DB ranks composite
# "device" parts (e.g. "MerR-pMerT mercury sensing chromoprotein
# reporter device") above bare promoters for queries like "mercury
# responsive promoter". A composite that already contains a reporter,
# placed in series with a separate reporter, gives a biologically
# nonsense circuit. We wrap circuit_builder.find_parts_for_node to
# drop obvious composites before _pick_best selects one, and fall back
# to the unfiltered list if NO atomic candidate survived so assembly
# never breaks on thin slots.
import circuits.circuit_builder as _cb  # noqa: E402

_COMPOSITE_KEYWORDS = ("reporter device", " device", "construct", "system", "biosensor")

def _field(p, name: str) -> str:
    """Safely get a field from a BioPart object OR a dict (store.search() returns dicts)."""
    if isinstance(p, dict):
        v = p.get(name)
    else:
        v = getattr(p, name, None)
    return (v or "") if v else ""

def _looks_composite(p) -> bool:
    blob = " ".join(filter(None, [
        _field(p, "name").lower(),
        _field(p, "description").lower(),
        _field(p, "function").lower(),
        _field(p, "title").lower(),
    ]))
    return any(kw in blob for kw in _COMPOSITE_KEYWORDS)

_orig_find = _cb.find_parts_for_node

def _find_parts_noncomposite(node, organism: str = "", limit: int = 5):
    # Oversample so the filter has room to drop composites without starving the selector.
    parts = _orig_find(node, organism=organism, limit=max(limit * 3, 15))
    atomic = [p for p in parts if not _looks_composite(p)]
    chosen = atomic if atomic else parts
    return chosen[:limit]

_cb.find_parts_for_node = _find_parts_noncomposite

# Wrap the vector-store singleton's .search to (a) filter composites and
# (b) derive a real organism from the description text, since the ingestion
# pipeline (ingest_igem.py line 161) hardcodes organism="Escherichia coli"
# for every iGEM part regardless of actual source. Both downstream paths —
# the MCP `search_parts` tool and circuit_builder.find_parts_for_node —
# go through store.search, so one patch fixes both.
import re  # noqa: E402
import mcp_server as _mcps  # noqa: E402

# Order matters: check longest/most-specific names first so "E. coli" doesn't
# accidentally match "Escherichia coli Nissle" etc., and more specific species
# names beat generic genus names.
_ORGANISM_PATTERNS = [
    (re.compile(r"\b(?:e\.?\s*coli|escherichia\s*coli)\b", re.I), "Escherichia coli"),
    (re.compile(r"\b(?:b\.?\s*subtilis|bacillus\s*subtilis)\b", re.I), "Bacillus subtilis"),
    (re.compile(r"\b(?:s\.?\s*aureus|staphylococcus\s*aureus|staphylococcus)\b", re.I), "Staphylococcus"),
    (re.compile(r"\b(?:p\.?\s*aeruginosa|pseudomonas\s*aeruginosa)\b", re.I), "Pseudomonas aeruginosa"),
    (re.compile(r"\b(?:p\.?\s*putida|pseudomonas\s*putida)\b", re.I), "Pseudomonas putida"),
    (re.compile(r"\bpseudomonas\b", re.I), "Pseudomonas"),
    (re.compile(r"\b(?:m\.?\s*tuberculosis|mycobacterium\s*tuberculosis)\b", re.I), "Mycobacterium tuberculosis"),
    (re.compile(r"\b(?:m\.?\s*marinum|mycobacterium\s*marinum)\b", re.I), "Mycobacterium marinum"),
    (re.compile(r"\bmycobacterium\b", re.I), "Mycobacterium"),
    (re.compile(r"\b(?:s\.?\s*cerevisiae|saccharomyces\s*cerevisiae)\b", re.I), "Saccharomyces cerevisiae"),
    (re.compile(r"\b(?:s\.?\s*pombe|schizosaccharomyces\s*pombe)\b", re.I), "Schizosaccharomyces pombe"),
    (re.compile(r"\b(?:c\.?\s*albicans|candida\s*albicans|candida)\b", re.I), "Candida albicans"),
    (re.compile(r"\byeast\b", re.I), "yeast (unspecified)"),
    (re.compile(r"\b(?:s\.?\s*flexneri|shigella\s*flexneri|shigella)\b", re.I), "Shigella flexneri"),
    (re.compile(r"\b(?:s\.?\s*marcescens|serratia\s*marcescens|serratia)\b", re.I), "Serratia marcescens"),
    (re.compile(r"\b(?:synechocystis)\b", re.I), "Synechocystis"),
    (re.compile(r"\b(?:cyanobacteria|cyanobacterium)\b", re.I), "cyanobacteria"),
    (re.compile(r"\b(?:mammal|human|mouse|HEK293|CHO)\b", re.I), "mammalian"),
]

def _infer_organism(p) -> str:
    """Scan description/function/source fields to find an organism mention."""
    text = " ".join(filter(None, [
        _field(p, "description"),
        _field(p, "function"),
        _field(p, "source"),
        _field(p, "source_database"),
    ]))
    if not text:
        return ""
    for pat, name in _ORGANISM_PATTERNS:
        if pat.search(text):
            return name
    return "unknown"

def _rewrite_organism(p):
    """Mutate in-place: replace the hardcoded organism with an inferred one."""
    inferred = _infer_organism(p)
    if inferred:
        if isinstance(p, dict):
            p["organism"] = inferred
        elif hasattr(p, "organism"):
            try:
                p.organism = inferred
            except Exception:
                pass
    return p

_store = _mcps._get_store()
_orig_store_search = _store.search

def _store_search_patched(query, limit: int = 10, part_type=None, **kwargs):
    hits = _orig_store_search(
        query=query,
        limit=max(limit * 3, 15),
        part_type=part_type,
        **kwargs,
    )
    atomic = [p for p in hits if not _looks_composite(p)]
    chosen = (atomic if atomic else hits)[:limit]
    for p in chosen:
        _rewrite_organism(p)
    return chosen

_store.search = _store_search_patched

if __name__ == "__main__":
    mcp.run(transport="sse")
