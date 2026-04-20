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

def _looks_composite(p) -> bool:
    blob = " ".join(filter(None, [
        (getattr(p, "name", "") or "").lower(),
        (getattr(p, "description", "") or "").lower(),
        (getattr(p, "function", "") or "").lower(),
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

# Also filter composites out of the direct search_parts() path so the MCP
# `search_parts` tool (which Gemini calls when it wants candidates for a
# specific slot) returns clean atomic parts too. Oversample, filter, trim.
import tools.search_parts as _sp  # noqa: E402

_orig_search = _sp.search_parts

def _search_parts_noncomposite(query, limit: int = 5, part_type=None, **kwargs):
    hits = _orig_search(query, limit=max(limit * 3, 15), part_type=part_type, **kwargs)
    atomic = [p for p in hits if not _looks_composite(p)]
    chosen = atomic if atomic else hits
    return chosen[:limit]

_sp.search_parts = _search_parts_noncomposite

# mcp_server.py bound `search_parts` at import time
# (`from tools.search_parts import search_parts`), so the MCP tool wrapper
# still points at the original. Rebind the local name.
import mcp_server as _mcps  # noqa: E402
_mcps.search_parts = _search_parts_noncomposite

if __name__ == "__main__":
    mcp.run(transport="sse")
