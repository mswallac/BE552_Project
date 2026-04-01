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
