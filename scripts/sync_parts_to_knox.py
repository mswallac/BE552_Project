"""
Sync biological parts from MCPGeneBank's vector store into Knox design spaces.

Usage:
    python scripts/sync_parts_to_knox.py --query "arsenic biosensor" --knox-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import csv
import io
import sys
from pathlib import Path

import httpx

# ---------------------------------------------------------------------------
# Knox role mapping
# ---------------------------------------------------------------------------

# Knox's DesignSpaceService.convertCSVRole() recognizes these exact strings:
#   promoter, cds, ribosomeBindingSite, terminator, assemblyScar, spacer
# Everything else is stored as-is (raw SO URI or string).
# MCPGeneBank PartType values: promoter, reporter, regulator, enzyme,
#   terminator, rbs, coding, plasmid, receptor, signal_peptide, toxin,
#   antitoxin, other

_BIOPART_TYPE_TO_KNOX_ROLE = {
    "promoter": "promoter",
    "rbs": "ribosomeBindingSite",
    "terminator": "terminator",
    # All protein-coding types map to CDS
    "cds": "cds",
    "coding": "cds",
    "reporter": "cds",
    "regulator": "cds",
    "enzyme": "cds",
    "plasmid": "cds",
    "receptor": "cds",
    "signal_peptide": "cds",
    "toxin": "cds",
    "antitoxin": "cds",
    "other": "cds",
}


def biopart_type_to_knox_role(biopart_type: str) -> str:
    """Map a MCPGeneBank PartType string to a Knox CSV role string."""
    return _BIOPART_TYPE_TO_KNOX_ROLE.get(biopart_type, "cds")


# ---------------------------------------------------------------------------
# Component ID encoding
# ---------------------------------------------------------------------------
# Knox stores componentIDs as opaque strings. We encode name + tags so
# Knox's AI tools can see them when inspecting design spaces.
# Format: {part_id}__{name_underscored}__{tag1_tag2_tag3}
# Double-underscore (__) separates fields. Spaces become _, spaces in tags
# become hyphens.


def encode_component_id(part_id: str, name: str, tags: list[str]) -> str:
    """Encode BioPart metadata into a Knox-safe componentID string."""
    safe_name = name.replace(" ", "_")
    safe_tags = "_".join(t.replace(" ", "-") for t in tags)
    if safe_tags:
        return f"{part_id}__{safe_name}__{safe_tags}"
    return f"{part_id}__{safe_name}"


def decode_component_id(encoded: str) -> tuple[str, str, list[str]]:
    """Decode a Knox componentID back into (part_id, name, tags)."""
    parts = encoded.split("__", 2)
    part_id = parts[0]
    name = parts[1].replace("_", " ") if len(parts) > 1 else ""
    tags = []
    if len(parts) > 2 and parts[2]:
        tags = [t.replace("-", " ") for t in parts[2].split("_")]
    return part_id, name, tags


# ---------------------------------------------------------------------------
# Knox CSV generation
# ---------------------------------------------------------------------------


def generate_knox_csvs(parts: list[dict]) -> tuple[str, str]:
    """
    Generate Knox-compatible CSV content from a list of BioPart dicts.

    Returns (components_csv, designs_csv) as strings.

    Components CSV: id,role,sequence — maps encoded componentID to role + sequence.
    Designs CSV: design — one row per part (catalog mode, not assembled circuits).
    """
    # Build components CSV
    comp_buf = io.StringIO()
    comp_writer = csv.writer(comp_buf)
    comp_writer.writerow(["id", "role", "sequence"])

    encoded_ids = []
    for p in parts:
        enc_id = encode_component_id(
            part_id=p["part_id"],
            name=p.get("name", ""),
            tags=p.get("tags", []),
        )
        role = biopart_type_to_knox_role(p.get("type", "other"))
        sequence = p.get("sequence", "")
        comp_writer.writerow([enc_id, role, sequence])
        encoded_ids.append(enc_id)

    # Build designs CSV — each part as its own single-element design
    design_buf = io.StringIO()
    design_writer = csv.writer(design_buf)
    design_writer.writerow(["design"])
    for enc_id in encoded_ids:
        design_writer.writerow([enc_id])

    return comp_buf.getvalue(), design_buf.getvalue()


# ---------------------------------------------------------------------------
# Parts fetcher
# ---------------------------------------------------------------------------


def _setup_mcpgenebank_path(mcpgenebank_dir: str) -> None:
    """Add MCPGeneBank's bio-circuit-ai directory to sys.path for imports."""
    mcpdir = str(Path(mcpgenebank_dir).resolve())
    if mcpdir not in sys.path:
        sys.path.insert(0, mcpdir)


def fetch_parts(
    queries: list[str],
    limit: int = 50,
    mcpgenebank_dir: str = "",
    use_demo_seed: bool = False,
) -> list[dict]:
    """
    Fetch BioParts from MCPGeneBank's vector store.

    Args:
        queries: Search queries (e.g., ["arsenic", "GFP"]).
        limit: Max results per query.
        mcpgenebank_dir: Path to MCPGeneBank/bio-circuit-ai/.
        use_demo_seed: If True, seed an in-memory store with demo data
                       (no Qdrant needed).

    Returns:
        List of BioPart dicts with all fields.
    """
    if not mcpgenebank_dir:
        mcpgenebank_dir = str(
            Path(__file__).resolve().parent.parent / "MCPGeneBank" / "bio-circuit-ai"
        )
    _setup_mcpgenebank_path(mcpgenebank_dir)

    if use_demo_seed:
        from database.vector_store import VectorStore
        store = VectorStore(in_memory=True)
        from demo import SEED_PARTS
        from models.part import BioPart
        store.upsert_parts([BioPart(**d) for d in SEED_PARTS])
    else:
        from database.vector_store import get_vector_store
        store = get_vector_store()

    seen_ids: set[str] = set()
    results: list[dict] = []

    for query in queries:
        hits = store.search(query=query, limit=limit)
        for hit in hits:
            pid = hit.get("part_id", "")
            if pid and pid not in seen_ids:
                seen_ids.add(pid)
                results.append(hit)

    return results


# ---------------------------------------------------------------------------
# Knox uploader
# ---------------------------------------------------------------------------


def build_upload_payload(
    comp_csv: str,
    design_csv: str,
    space_prefix: str,
    group_id: str | None,
) -> tuple[list[tuple], dict]:
    """
    Build the multipart files list and form data dict for Knox CSV import.

    Knox POST /import/csv expects:
      - inputCSVFiles[]: multipart file uploads (components CSV + designs CSV)
      - outputSpacePrefix: string
      - groupID: string (optional, defaults to "none")
      - weight: string (optional, defaults to "0.0")
    """
    files = [
        ("inputCSVFiles[]", ("components.csv", comp_csv.encode(), "text/csv")),
        ("inputCSVFiles[]", ("designs.csv", design_csv.encode(), "text/csv")),
    ]
    data = {
        "outputSpacePrefix": space_prefix,
        "groupID": group_id if group_id else "none",
        "weight": "0.0",
    }
    return files, data


def upload_to_knox(
    comp_csv: str,
    design_csv: str,
    space_prefix: str,
    knox_url: str = "http://localhost:8080",
    group_id: str | None = None,
    use_or: bool = False,
) -> dict:
    """
    Upload CSV files to Knox via its REST API.
    """
    endpoint = "/or/csv" if use_or else "/import/csv"
    url = f"{knox_url.rstrip('/')}{endpoint}"

    files, data = build_upload_payload(comp_csv, design_csv, space_prefix, group_id)

    try:
        resp = httpx.post(url, files=files, data=data, timeout=30.0)
        if resp.status_code in (200, 204):
            return {"success": True, "status_code": resp.status_code,
                    "message": f"Uploaded to Knox space '{space_prefix}'"}
        return {"success": False, "status_code": resp.status_code,
                "message": f"Knox returned {resp.status_code}: {resp.text}"}
    except httpx.ConnectError:
        return {"success": False, "status_code": 0,
                "message": f"Cannot connect to Knox at {knox_url}. Is it running? Try: docker-compose up"}
    except httpx.TimeoutException:
        return {"success": False, "status_code": 0,
                "message": f"Request to Knox timed out"}


if __name__ == "__main__":
    pass
