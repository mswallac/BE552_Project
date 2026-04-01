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


if __name__ == "__main__":
    pass
