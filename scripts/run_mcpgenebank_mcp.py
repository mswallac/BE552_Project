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

if __name__ == "__main__":
    mcp.run(transport="sse")
