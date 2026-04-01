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
