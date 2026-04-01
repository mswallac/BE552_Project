# MCPGeneBank → Knox Parts Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standalone Python script that fetches BioParts from MCPGeneBank's vector store, formats them as Knox-compatible CSV files, and uploads them to Knox's REST API — preserving part IDs, names, roles, sequences, descriptions, and tags.

**Architecture:** The script imports MCPGeneBank's existing `search_parts()` and `get_vector_store()` directly (via sys.path). It produces two CSV files per upload: a components file (maps part IDs to roles + sequences) and a designs file (enumerates parts as single-part rows). These are POSTed as multipart form data to Knox's `POST /import/csv` endpoint. An optional `--or` flag uses `POST /or/csv` to merge into a single design space.

**Tech Stack:** Python 3.11, httpx, MCPGeneBank internals (models.part, database.vector_store, tools.search_parts, ingestion.\*)

**Key Knox CSV format details:**
- Components CSV: first row `id,role,sequence` — subsequent rows map each part_id to its SO role string and DNA sequence
- Designs CSV: first row `design` — subsequent rows are comma-separated part_id lists
- Knox role strings: `promoter`, `cds`, `ribosomeBindingSite`, `terminator`, `assemblyScar`, `spacer` (mapped to SO URIs internally by `convertCSVRole`)
- Knox endpoint: `POST /import/csv` accepts `inputCSVFiles[]` (multipart), `outputSpacePrefix` (string), optional `groupID` and `weight`
- Part metadata (name, description, tags) is encoded into the componentID string as `partid__Name__tag1_tag2` so Knox's AI tools can see it

---

### Task 1: Project scaffolding and BioPart type-to-Knox-role mapping

**Files:**
- Create: `scripts/sync_parts_to_knox.py`
- Create: `tests/test_sync_parts.py`

This task creates the script entry point and the mapping function that converts MCPGeneBank's `PartType` enum values to Knox's expected CSV role strings.

- [ ] **Step 1: Write the failing test for role mapping**

```python
# tests/test_sync_parts.py
import sys
import os

# Add scripts dir to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))


def test_biopart_type_to_knox_role():
    from sync_parts_to_knox import biopart_type_to_knox_role

    assert biopart_type_to_knox_role("promoter") == "promoter"
    assert biopart_type_to_knox_role("reporter") == "cds"
    assert biopart_type_to_knox_role("regulator") == "cds"
    assert biopart_type_to_knox_role("enzyme") == "cds"
    assert biopart_type_to_knox_role("coding") == "cds"
    assert biopart_type_to_knox_role("rbs") == "ribosomeBindingSite"
    assert biopart_type_to_knox_role("terminator") == "terminator"
    assert biopart_type_to_knox_role("plasmid") == "cds"
    assert biopart_type_to_knox_role("other") == "cds"


def test_biopart_type_to_knox_role_unknown_defaults_to_cds():
    from sync_parts_to_knox import biopart_type_to_knox_role

    assert biopart_type_to_knox_role("something_new") == "cds"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_sync_parts.py::test_biopart_type_to_knox_role -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'sync_parts_to_knox'`

- [ ] **Step 3: Create the script with role mapping**

```python
# scripts/sync_parts_to_knox.py
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


if __name__ == "__main__":
    pass
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/test_sync_parts.py -v`
Expected: 2 passed

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_parts_to_knox.py tests/test_sync_parts.py
git commit -m "feat: scaffold sync_parts_to_knox with role mapping"
```

---

### Task 2: Encode BioPart metadata into Knox-compatible component ID

**Files:**
- Modify: `scripts/sync_parts_to_knox.py`
- Modify: `tests/test_sync_parts.py`

Knox stores componentIDs as opaque strings on graph edges. We encode the BioPart's name and tags into the ID so Knox's AI chat tools can see them when reasoning about designs.

Format: `BBa_K1031907__Pars_Arsenic_Sensing_Promoter__arsenic_metal-sensing_biosensor`

Uses double-underscore `__` as field separator (safe — never appears in iGEM/GenBank IDs) and single underscore/hyphen within fields.

- [ ] **Step 1: Write the failing test**

```python
# tests/test_sync_parts.py — append

def test_encode_component_id_full():
    from sync_parts_to_knox import encode_component_id

    result = encode_component_id(
        part_id="BBa_K1031907",
        name="Pars Arsenic Sensing Promoter",
        tags=["arsenic", "metal sensing", "biosensor"],
    )
    assert result == "BBa_K1031907__Pars_Arsenic_Sensing_Promoter__arsenic_metal-sensing_biosensor"


def test_encode_component_id_no_tags():
    from sync_parts_to_knox import encode_component_id

    result = encode_component_id(
        part_id="BBa_E0040",
        name="GFP (Green Fluorescent Protein)",
        tags=[],
    )
    assert result == "BBa_E0040__GFP_(Green_Fluorescent_Protein)"


def test_encode_component_id_no_name():
    from sync_parts_to_knox import encode_component_id

    result = encode_component_id(part_id="BBa_B0034", name="", tags=["translation"])
    assert result == "BBa_B0034____translation"


def test_decode_component_id():
    from sync_parts_to_knox import decode_component_id

    part_id, name, tags = decode_component_id(
        "BBa_K1031907__Pars_Arsenic_Sensing_Promoter__arsenic_metal-sensing_biosensor"
    )
    assert part_id == "BBa_K1031907"
    assert name == "Pars Arsenic Sensing Promoter"
    assert tags == ["arsenic", "metal sensing", "biosensor"]


def test_decode_component_id_no_tags():
    from sync_parts_to_knox import decode_component_id

    part_id, name, tags = decode_component_id("BBa_E0040__GFP_(Green_Fluorescent_Protein)")
    assert part_id == "BBa_E0040"
    assert name == "GFP (Green Fluorescent Protein)"
    assert tags == []
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/test_sync_parts.py -k "encode or decode" -v`
Expected: FAIL — `ImportError: cannot import name 'encode_component_id'`

- [ ] **Step 3: Implement encode/decode functions**

Add to `scripts/sync_parts_to_knox.py` after the role mapping:

```python
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/test_sync_parts.py -v`
Expected: 7 passed

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_parts_to_knox.py tests/test_sync_parts.py
git commit -m "feat: encode/decode BioPart metadata in Knox componentIDs"
```

---

### Task 3: Generate Knox CSV files from BioPart list

**Files:**
- Modify: `scripts/sync_parts_to_knox.py`
- Modify: `tests/test_sync_parts.py`

Knox CSV import expects two files uploaded together:
1. **Components CSV** — header `id,role,sequence`, maps each componentID to its SO role and DNA sequence
2. **Designs CSV** — header `design`, each row is a comma-separated ordered list of componentIDs (one part per row for a parts catalog)

- [ ] **Step 1: Write the failing test**

```python
# tests/test_sync_parts.py — append

def _make_biopart(part_id, name, part_type, sequence="ATGC", tags=None, organism="E. coli",
                  function="", description="", source_database="igem", references=None):
    """Helper to create a dict mimicking BioPart.model_dump() output."""
    return {
        "part_id": part_id,
        "name": name,
        "type": part_type,
        "sequence": sequence,
        "tags": tags or [],
        "organism": organism,
        "function": function,
        "description": description,
        "source_database": source_database,
        "references": references or [],
    }


def test_generate_knox_csvs():
    from sync_parts_to_knox import generate_knox_csvs

    parts = [
        _make_biopart("BBa_J23100", "Constitutive Promoter", "promoter",
                       sequence="TTGACAGC", tags=["constitutive"]),
        _make_biopart("BBa_E0040", "GFP", "reporter",
                       sequence="ATGAGTAAA", tags=["fluorescence", "green"]),
        _make_biopart("BBa_B0034", "RBS B0034", "rbs",
                       sequence="AAAGAG", tags=["translation"]),
    ]

    comp_csv, design_csv = generate_knox_csvs(parts)

    # Parse components CSV
    comp_reader = csv.reader(io.StringIO(comp_csv))
    comp_rows = list(comp_reader)
    assert comp_rows[0] == ["id", "role", "sequence"]
    assert len(comp_rows) == 4  # header + 3 parts

    # Check that componentIDs contain encoded metadata
    assert "BBa_J23100__" in comp_rows[1][0]
    assert comp_rows[1][1] == "promoter"
    assert comp_rows[1][2] == "TTGACAGC"

    assert "BBa_E0040__" in comp_rows[2][0]
    assert comp_rows[2][1] == "cds"

    assert "BBa_B0034__" in comp_rows[3][0]
    assert comp_rows[3][1] == "ribosomeBindingSite"

    # Parse designs CSV
    design_reader = csv.reader(io.StringIO(design_csv))
    design_rows = list(design_reader)
    assert design_rows[0] == ["design"]
    # Each part is its own single-part design row
    assert len(design_rows) == 4  # header + 3 parts


def test_generate_knox_csvs_empty():
    from sync_parts_to_knox import generate_knox_csvs

    comp_csv, design_csv = generate_knox_csvs([])
    comp_reader = csv.reader(io.StringIO(comp_csv))
    comp_rows = list(comp_reader)
    assert comp_rows == [["id", "role", "sequence"]]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/test_sync_parts.py::test_generate_knox_csvs -v`
Expected: FAIL — `ImportError: cannot import name 'generate_knox_csvs'`

- [ ] **Step 3: Implement CSV generation**

Add to `scripts/sync_parts_to_knox.py`:

```python
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/test_sync_parts.py -v`
Expected: 9 passed

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_parts_to_knox.py tests/test_sync_parts.py
git commit -m "feat: generate Knox-compatible CSV from BioPart list"
```

---

### Task 4: Parts fetcher — query MCPGeneBank vector store

**Files:**
- Modify: `scripts/sync_parts_to_knox.py`
- Modify: `tests/test_sync_parts.py`

This adds the function that imports MCPGeneBank's vector store and search functions to retrieve parts. It adds MCPGeneBank's `bio-circuit-ai/` directory to `sys.path` at runtime.

- [ ] **Step 1: Write the failing test**

```python
# tests/test_sync_parts.py — append

def test_fetch_parts_from_demo_seed():
    """
    Integration test: requires MCPGeneBank to be importable.
    Uses the demo seed data (in-memory vector store) so no Qdrant needed.
    """
    from sync_parts_to_knox import fetch_parts

    # fetch_parts with use_demo_seed=True loads demo.py's SEED_PARTS into
    # an in-memory store and searches it.
    parts = fetch_parts(
        queries=["arsenic"],
        limit=5,
        mcpgenebank_dir=str(Path(__file__).parent.parent / "MCPGeneBank" / "bio-circuit-ai"),
        use_demo_seed=True,
    )

    assert len(parts) > 0
    assert all(isinstance(p, dict) for p in parts)
    # Every part must have the fields we need
    for p in parts:
        assert "part_id" in p
        assert "name" in p
        assert "type" in p
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_sync_parts.py::test_fetch_parts_from_demo_seed -v`
Expected: FAIL — `ImportError: cannot import name 'fetch_parts'`

- [ ] **Step 3: Implement fetch_parts**

Add to `scripts/sync_parts_to_knox.py`:

```python
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
        # Import seed parts from demo.py
        from demo import SEED_PARTS
        store.upsert_parts(SEED_PARTS)
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_sync_parts.py::test_fetch_parts_from_demo_seed -v`
Expected: PASS (may take a few seconds for embedding model to load on first run)

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_parts_to_knox.py tests/test_sync_parts.py
git commit -m "feat: fetch parts from MCPGeneBank vector store"
```

---

### Task 5: Knox uploader — POST CSV files via multipart form

**Files:**
- Modify: `scripts/sync_parts_to_knox.py`
- Modify: `tests/test_sync_parts.py`

This adds the HTTP client that uploads the two CSV files to Knox's `/import/csv` endpoint.

- [ ] **Step 1: Write the failing test**

```python
# tests/test_sync_parts.py — append

def test_build_upload_payload():
    """Test that the multipart payload is structured correctly for Knox."""
    from sync_parts_to_knox import build_upload_payload

    comp_csv = "id,role,sequence\nBBa_E0040__GFP,cds,ATGAGT\n"
    design_csv = "design\nBBa_E0040__GFP\n"

    files, data = build_upload_payload(
        comp_csv=comp_csv,
        design_csv=design_csv,
        space_prefix="test_space",
        group_id="test_group",
    )

    # Knox expects inputCSVFiles[] as the multipart field name
    assert len(files) == 2
    assert files[0][0] == "inputCSVFiles[]"
    assert files[1][0] == "inputCSVFiles[]"
    assert data["outputSpacePrefix"] == "test_space"
    assert data["groupID"] == "test_group"


def test_build_upload_payload_no_group():
    from sync_parts_to_knox import build_upload_payload

    comp_csv = "id,role,sequence\n"
    design_csv = "design\n"

    files, data = build_upload_payload(comp_csv, design_csv, "sp", None)

    assert "groupID" not in data or data["groupID"] == "none"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/test_sync_parts.py -k "upload_payload" -v`
Expected: FAIL — `ImportError: cannot import name 'build_upload_payload'`

- [ ] **Step 3: Implement upload functions**

Add to `scripts/sync_parts_to_knox.py`:

```python
import httpx

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

    Args:
        comp_csv: Components CSV string content.
        design_csv: Designs CSV string content.
        space_prefix: Knox design space prefix name.
        knox_url: Knox server base URL.
        group_id: Optional Knox group ID.
        use_or: If True, use POST /or/csv (union into single space)
                instead of POST /import/csv (one space per design).

    Returns:
        dict with keys: success (bool), status_code (int), message (str)
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/test_sync_parts.py -v`
Expected: 11 passed

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_parts_to_knox.py tests/test_sync_parts.py
git commit -m "feat: Knox CSV upload via multipart POST"
```

---

### Task 6: CLI argument parser and main() orchestration

**Files:**
- Modify: `scripts/sync_parts_to_knox.py`
- Modify: `tests/test_sync_parts.py`

Wire everything together: parse CLI args → fetch parts → generate CSVs → upload (or dry-run).

- [ ] **Step 1: Write the failing test for dry-run mode**

```python
# tests/test_sync_parts.py — append

import tempfile


def test_main_dry_run(tmp_path):
    """Test the full pipeline in dry-run mode (no Knox needed)."""
    from sync_parts_to_knox import main

    output_file = tmp_path / "export.csv"

    exit_code = main([
        "--query", "arsenic",
        "--limit", "3",
        "--dry-run",
        "--output", str(output_file),
        "--mcpgenebank-dir", str(Path(__file__).parent.parent / "MCPGeneBank" / "bio-circuit-ai"),
        "--demo-seed",
    ])

    assert exit_code == 0
    # Dry run should write files
    comp_file = tmp_path / "export_components.csv"
    design_file = tmp_path / "export_designs.csv"
    assert comp_file.exists() or output_file.exists()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_sync_parts.py::test_main_dry_run -v`
Expected: FAIL — `ImportError: cannot import name 'main'`

- [ ] **Step 3: Implement CLI and main()**

Replace the `if __name__ == "__main__": pass` block at the bottom of `scripts/sync_parts_to_knox.py` with:

```python
# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Sync biological parts from MCPGeneBank into Knox design spaces.",
    )
    parser.add_argument(
        "--query", nargs="+", required=True,
        help="One or more search queries (e.g., --query arsenic GFP)",
    )
    parser.add_argument("--limit", type=int, default=50, help="Max parts per query (default: 50)")
    parser.add_argument("--knox-url", default="http://localhost:8080", help="Knox server URL")
    parser.add_argument("--space-id", default="", help="Knox design space prefix (auto-generated if omitted)")
    parser.add_argument("--group-id", default=None, help="Knox group ID")
    parser.add_argument("--or-mode", action="store_true", help="Use OR import (merge into single design space)")
    parser.add_argument("--dry-run", action="store_true", help="Generate CSVs without uploading to Knox")
    parser.add_argument("--output", default="", help="Write CSV files to this path prefix (implies --dry-run)")
    parser.add_argument(
        "--mcpgenebank-dir", default="",
        help="Path to MCPGeneBank/bio-circuit-ai/ (auto-detected if omitted)",
    )
    parser.add_argument("--demo-seed", action="store_true", help="Use demo seed data (no Qdrant needed)")

    args = parser.parse_args(argv)

    if args.output:
        args.dry_run = True

    # Generate space prefix from queries if not provided
    space_prefix = args.space_id or "_".join(args.query)[:60].replace(" ", "_")

    # 1. Fetch parts
    print(f"Fetching parts for queries: {args.query} (limit {args.limit} per query)...")
    parts = fetch_parts(
        queries=args.query,
        limit=args.limit,
        mcpgenebank_dir=args.mcpgenebank_dir,
        use_demo_seed=args.demo_seed,
    )
    print(f"  Found {len(parts)} unique parts")

    if not parts:
        print("No parts found. Try broader queries or use --demo-seed.")
        return 1

    # 2. Generate CSVs
    comp_csv, design_csv = generate_knox_csvs(parts)

    # 3. Upload or save
    if args.dry_run:
        out_base = args.output or f"{space_prefix}_export"
        comp_path = Path(f"{out_base}_components.csv")
        design_path = Path(f"{out_base}_designs.csv")
        comp_path.write_text(comp_csv)
        design_path.write_text(design_csv)
        print(f"  Components CSV: {comp_path}")
        print(f"  Designs CSV:    {design_path}")
        print("Dry run complete — files written, nothing uploaded.")
        return 0

    print(f"Uploading to Knox at {args.knox_url} as '{space_prefix}'...")
    result = upload_to_knox(
        comp_csv=comp_csv,
        design_csv=design_csv,
        space_prefix=space_prefix,
        knox_url=args.knox_url,
        group_id=args.group_id,
        use_or=args.or_mode,
    )

    if result["success"]:
        print(f"  {result['message']}")
        print(f"  {len(parts)} parts loaded into Knox.")
        return 0
    else:
        print(f"  FAILED: {result['message']}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/test_sync_parts.py -v`
Expected: 12 passed

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_parts_to_knox.py tests/test_sync_parts.py
git commit -m "feat: CLI and main() for sync_parts_to_knox"
```

---

### Task 7: End-to-end integration test with Knox (manual verification)

**Files:**
- No new files — this is a manual verification task

This task verifies the full pipeline works with a real Knox instance.

- [ ] **Step 1: Start Knox**

```bash
cd Knox_BE552/knox-master && docker-compose up --build -d
```

Wait for Knox to be healthy: `curl -s http://localhost:8080/designSpace/list`

- [ ] **Step 2: Run sync with demo seed data**

```bash
python scripts/sync_parts_to_knox.py \
  --query "arsenic" "GFP" "tetracycline" \
  --limit 10 \
  --knox-url http://localhost:8080 \
  --space-id "mcpgenebank_parts" \
  --or-mode \
  --demo-seed
```

Expected output:
```
Fetching parts for queries: ['arsenic', 'GFP', 'tetracycline'] (limit 10 per query)...
  Found N unique parts
Uploading to Knox at http://localhost:8080 as 'mcpgenebank_parts'...
  Uploaded to Knox space 'mcpgenebank_parts'
  N parts loaded into Knox.
```

- [ ] **Step 3: Verify in Knox**

Open http://localhost:8080 and confirm:
- A design space named `mcpgenebank_parts` exists
- Clicking it shows a graph with edges labeled with encoded componentIDs
- The componentIDs contain part names and tags (e.g., `BBa_K1031907__Pars_Arsenic_Sensing_Promoter__arsenic_metal-sensing_biosensor`)

- [ ] **Step 4: Verify via Knox AI chat**

In Knox's AI chat, ask: "What parts are in the mcpgenebank_parts design space?"

The AI should be able to read the encoded names and tags from the componentIDs and describe the parts.

- [ ] **Step 5: Commit any fixes and tag**

```bash
git add -A
git commit -m "test: verify end-to-end Knox integration"
```

---

### Task 8: Summary output and description preservation

**Files:**
- Modify: `scripts/sync_parts_to_knox.py`
- Modify: `tests/test_sync_parts.py`

Add a summary report printed after upload showing what was synced, and add an optional `--descriptions-file` that writes a JSON sidecar mapping componentIDs to their full descriptions (for reference, since the CSV encoding is lossy on description text).

- [ ] **Step 1: Write the failing test**

```python
# tests/test_sync_parts.py — append

import json


def test_generate_descriptions_sidecar():
    from sync_parts_to_knox import generate_descriptions_sidecar

    parts = [
        _make_biopart("BBa_J23100", "Constitutive Promoter", "promoter",
                       tags=["constitutive"], function="Strong constitutive promoter",
                       description="Widely used in iGEM", organism="E. coli",
                       source_database="igem", references=["https://parts.igem.org/Part:BBa_J23100"]),
    ]

    sidecar = generate_descriptions_sidecar(parts)
    parsed = json.loads(sidecar)

    assert "BBa_J23100" in parsed
    entry = parsed["BBa_J23100"]
    assert entry["name"] == "Constitutive Promoter"
    assert entry["function"] == "Strong constitutive promoter"
    assert entry["description"] == "Widely used in iGEM"
    assert entry["organism"] == "E. coli"
    assert entry["source"] == "igem"
    assert "https://parts.igem.org/Part:BBa_J23100" in entry["references"]
    assert entry["tags"] == ["constitutive"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_sync_parts.py::test_generate_descriptions_sidecar -v`
Expected: FAIL — `ImportError`

- [ ] **Step 3: Implement descriptions sidecar**

Add to `scripts/sync_parts_to_knox.py`:

```python
import json as _json

# ---------------------------------------------------------------------------
# Descriptions sidecar
# ---------------------------------------------------------------------------


def generate_descriptions_sidecar(parts: list[dict]) -> str:
    """
    Generate a JSON file mapping part_id to full metadata.

    This preserves description, function, organism, references, and other
    fields that don't fit in the Knox CSV encoding.
    """
    sidecar = {}
    for p in parts:
        sidecar[p["part_id"]] = {
            "name": p.get("name", ""),
            "type": p.get("type", ""),
            "function": p.get("function", ""),
            "description": p.get("description", ""),
            "organism": p.get("organism", ""),
            "source": p.get("source_database", ""),
            "references": p.get("references", []),
            "tags": p.get("tags", []),
            "sequence_length": len(p.get("sequence", "")),
        }
    return _json.dumps(sidecar, indent=2)
```

Then update `main()` to write the sidecar. After the `generate_knox_csvs` call, add:

```python
    # Generate descriptions sidecar
    sidecar_json = generate_descriptions_sidecar(parts)

    # 3. Upload or save
    if args.dry_run:
        out_base = args.output or f"{space_prefix}_export"
        comp_path = Path(f"{out_base}_components.csv")
        design_path = Path(f"{out_base}_designs.csv")
        sidecar_path = Path(f"{out_base}_descriptions.json")
        comp_path.write_text(comp_csv)
        design_path.write_text(design_csv)
        sidecar_path.write_text(sidecar_json)
        print(f"  Components CSV:    {comp_path}")
        print(f"  Designs CSV:       {design_path}")
        print(f"  Descriptions JSON: {sidecar_path}")
        print("Dry run complete — files written, nothing uploaded.")
        return 0
```

And after a successful upload, also write the sidecar:

```python
    if result["success"]:
        print(f"  {result['message']}")
        print(f"  {len(parts)} parts loaded into Knox.")
        # Write descriptions sidecar alongside
        sidecar_path = Path(f"{space_prefix}_descriptions.json")
        sidecar_path.write_text(sidecar_json)
        print(f"  Descriptions JSON: {sidecar_path}")
        return 0
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/test_sync_parts.py -v`
Expected: 13 passed

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_parts_to_knox.py tests/test_sync_parts.py
git commit -m "feat: descriptions sidecar JSON for full metadata preservation"
```

---

### Task 9: Final cleanup and push

**Files:**
- Modify: `CLAUDE.md` (add sync script documentation)

- [ ] **Step 1: Add sync script to CLAUDE.md**

Append to the "Build & Run Commands" section under a new sub-heading:

```markdown
### Syncing Parts: MCPGeneBank → Knox
```bash
# Dry run with demo data (no Qdrant or Knox needed)
python scripts/sync_parts_to_knox.py --query "arsenic" "GFP" --demo-seed --dry-run

# Upload to Knox (requires Knox running at localhost:8080)
python scripts/sync_parts_to_knox.py --query "arsenic" "GFP" "tetracycline" --or-mode --demo-seed

# With live Qdrant vector store (after running scrape_300.py)
python scripts/sync_parts_to_knox.py --query "arsenic biosensor" --limit 50 --or-mode
```
```

- [ ] **Step 2: Run full test suite**

Run: `pytest tests/test_sync_parts.py -v`
Expected: All 13 tests pass

- [ ] **Step 3: Commit and push**

```bash
git add CLAUDE.md scripts/sync_parts_to_knox.py tests/test_sync_parts.py
git commit -m "docs: add sync_parts_to_knox to CLAUDE.md"
git push origin master
```
