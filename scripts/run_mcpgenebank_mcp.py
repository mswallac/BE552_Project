"""
Launcher for MCPGeneBank's MCP server that applies two compat shims so Knox
(running inside docker) can consume it as an SSE MCP server from outside the
container:

1. FastMCP 1.27+ enables DNS-rebinding protection by default when bound to
   localhost, and only allows Host headers matching `127.0.0.1:*`,
   `localhost:*`, `[::1]:*`. Requests from Knox's container arrive with
   `Host: host.docker.internal` which get rejected → the SSE handshake
   aborts with "Request validation failed". We disable the protection for
   local development.

2. Spring AI 1.1.1's Google Gemini adapter NPEs when a tool's JSON schema
   contains `"default": null`. MCPGeneBank's `build_from_template` has an
   `Optional[dict]` parameter that emits that null default. We strip null
   `default` fields from every registered tool's schema before the server
   starts.

Previously this launcher also ran query-time filters to clean up junk parts
and infer real organism metadata. Those hacks are now gone — the MCPGeneBank
ingestion pipeline (mswallac/MCPGeneBank fork, `fix/clean-ingest` merged
into main) filters primers, devices, and composite cassettes at scrape time
and infers organism from description at ingest. If junk reappears in query
results, fix the ingester, don't monkey-patch here.

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
    # New BE552 deliverable layout — code lives under "Final Project BioPilot/Code/"
    HERE.parent / "Final Project BioPilot" / "Code" / "MCPGeneBank" / "bio-circuit-ai",
    # Backwards-compat: legacy flat layout (pre-relocation)
    HERE.parent / "MCPGeneBank" / "bio-circuit-ai",
]
for anc in HERE.parents:
    if anc.name == "BE552_Project":
        _CANDIDATES.append(anc / "Final Project BioPilot" / "Code" / "MCPGeneBank" / "bio-circuit-ai")
        _CANDIDATES.append(anc / "MCPGeneBank" / "bio-circuit-ai")
    _CANDIDATES.append(anc.parent / "BE552_Project" / "Final Project BioPilot" / "Code" / "MCPGeneBank" / "bio-circuit-ai")
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

# Shim 1: allow Knox's docker container to reach us via host.docker.internal.
mcp.settings.transport_security = TransportSecuritySettings(
    enable_dns_rebinding_protection=False,
)


# Shim 2: strip `"default": null` from tool schemas for Spring AI Gemini compat.
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


if __name__ == "__main__":
    mcp.run(transport="sse")
