import csv
import io
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


from pathlib import Path

def test_fetch_parts_from_demo_seed():
    """
    Integration test: requires MCPGeneBank to be importable.
    Uses the demo seed data (in-memory vector store) so no Qdrant needed.
    """
    from sync_parts_to_knox import fetch_parts

    parts = fetch_parts(
        queries=["arsenic"],
        limit=5,
        mcpgenebank_dir=str(Path(__file__).parent.parent / "MCPGeneBank" / "bio-circuit-ai"),
        use_demo_seed=True,
    )

    assert len(parts) > 0
    assert all(isinstance(p, dict) for p in parts)
    for p in parts:
        assert "part_id" in p
        assert "name" in p
        assert "type" in p
