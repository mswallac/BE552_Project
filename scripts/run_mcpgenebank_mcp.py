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

# Also filter composites out of the MCP `search_parts` tool path.
# That tool (in mcp_server.py) calls `_get_store().search(...)` directly on
# the vector-store singleton — it does NOT go through tools.search_parts.
# Patching the module-level name is a no-op; we have to wrap the store's
# bound `search` method on the actual singleton instance. Instantiate the
# store now (forces construction) and replace its .search.
import mcp_server as _mcps  # noqa: E402

_store = _mcps._get_store()
_orig_store_search = _store.search

def _store_search_noncomposite(query, limit: int = 10, part_type=None, **kwargs):
    hits = _orig_store_search(
        query=query,
        limit=max(limit * 3, 15),
        part_type=part_type,
        **kwargs,
    )
    atomic = [p for p in hits if not _looks_composite(p)]
    return (atomic if atomic else hits)[:limit]

_store.search = _store_search_noncomposite

if __name__ == "__main__":
    mcp.run(transport="sse")
