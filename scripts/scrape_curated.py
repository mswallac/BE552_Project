"""
Curated large-scale ingestion: ~8,000 vetted biological parts into Knox.

Targets:
  - UniProt Swiss-Prot: ALL reviewed E. coli proteins (~739) + fluorescent proteins (~1,010)
  - iGEM Registry: ~5,000 parts with sequences (broad query set)
  - Addgene: ~1,000 sequence-verified plasmids
  - GenBank: ~1,000 synthetic biology sequences

Then syncs everything to Knox via sync_parts_to_knox.

Usage:
    python scripts/scrape_curated.py                          # full run
    python scripts/scrape_curated.py --skip-knox              # scrape only, don't upload
    python scripts/scrape_curated.py --sources igem uniprot   # specific sources only
    python scripts/scrape_curated.py --knox-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import logging
import sys
import time
from pathlib import Path

# Add MCPGeneBank to path
MCPGENEBANK_DIR = str((Path(__file__).resolve().parent.parent / "MCPGeneBank" / "bio-circuit-ai"))
if MCPGENEBANK_DIR not in sys.path:
    sys.path.insert(0, MCPGENEBANK_DIR)

from models.part import BioPart

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(name)s %(levelname)s %(message)s",
)
logger = logging.getLogger("scrape_curated")

# ---------------------------------------------------------------------------
# UniProt: ALL reviewed E. coli + all fluorescent proteins
# ---------------------------------------------------------------------------

UNIPROT_QUERIES = [
    # All reviewed E. coli proteins (Swiss-Prot curated)
    ("reviewed:true AND organism_id:562", 500),
    # Fluorescent proteins across all organisms
    ("fluorescent protein AND reviewed:true", 500),
    # Transcription factors - broad
    ("transcription factor AND reviewed:true AND organism_id:562", 100),
    ("repressor AND reviewed:true AND organism_id:562", 100),
    ("activator AND reviewed:true AND organism_id:562", 100),
    # Metal sensing / biosensor relevant
    ("metal binding transcription AND reviewed:true", 100),
    ("arsenic AND reviewed:true", 50),
    ("mercury resistance AND reviewed:true", 50),
    ("copper binding AND reviewed:true", 50),
    # Enzymes for metabolic engineering
    ("biosynthetic enzyme AND reviewed:true AND organism_id:562", 100),
    ("synthase AND reviewed:true AND organism_id:562", 100),
    # Signaling
    ("quorum sensing AND reviewed:true", 100),
    ("sigma factor AND reviewed:true AND organism_id:562", 50),
    # Toxin-antitoxin for kill switches
    ("toxin antitoxin AND reviewed:true AND organism_id:562", 50),
    # CRISPR
    ("CRISPR associated AND reviewed:true", 100),
    # Recombinases
    ("recombinase integrase AND reviewed:true AND organism_id:562", 50),
    # Luciferases
    ("luciferase AND reviewed:true", 50),
]

# ---------------------------------------------------------------------------
# iGEM: broad coverage, sequence-required
# ---------------------------------------------------------------------------

IGEM_QUERIES = [
    # Core part types
    "promoter", "terminator", "ribosome binding site", "reporter",
    "coding", "regulatory", "inverter", "generator",
    # Specific well-known parts
    "GFP", "RFP", "YFP", "CFP", "mCherry", "luciferase",
    "LacI", "TetR", "AraC", "LuxR", "cI",
    # Inducible systems
    "IPTG", "tetracycline", "arabinose", "AHL", "quorum sensing",
    # Biosensors
    "biosensor", "arsenic", "mercury", "copper", "lead", "zinc", "cadmium",
    "metal sensing",
    # Circuit patterns
    "toggle switch", "repressilator", "oscillator", "kill switch",
    "logic gate", "cascade", "amplifier",
    # Functional categories
    "enzyme", "protease", "kinase", "recombinase",
    "antibiotic resistance", "selection marker",
    "T7", "sigma factor",
    "plasmid backbone",
    # Accessory
    "insulator", "spacer", "scar",
    "fluorescent", "luminescence",
]

# ---------------------------------------------------------------------------
# GenBank: synthetic biology focused
# ---------------------------------------------------------------------------

GENBANK_QUERIES = [
    ("synthetic promoter E. coli", 50),
    ("synthetic terminator E. coli", 50),
    ("ribosome binding site synthetic", 50),
    ("GFP reporter plasmid", 50),
    ("RFP mCherry reporter", 50),
    ("fluorescent protein reporter", 50),
    ("arsenic biosensor synthetic biology", 50),
    ("mercury biosensor genetic", 30),
    ("copper biosensor genetic", 30),
    ("transcription factor synthetic biology", 50),
    ("toggle switch genetic circuit", 50),
    ("repressilator oscillator", 30),
    ("quorum sensing AHL circuit", 30),
    ("IPTG inducible promoter", 50),
    ("tetracycline repressor TetR", 50),
    ("arabinose promoter pBAD", 50),
    ("T7 promoter expression", 50),
    ("lac operon regulatory", 50),
    ("kill switch toxin antitoxin", 30),
    ("CRISPR Cas9 guide RNA", 50),
    ("codon optimized synthetic gene", 50),
    ("sigma factor promoter E. coli", 30),
    ("genetic circuit logic gate", 30),
    ("metabolic pathway enzyme E. coli", 50),
]

# ---------------------------------------------------------------------------
# Addgene: sequence-verified plasmids
# ---------------------------------------------------------------------------

ADDGENE_QUERIES = [
    ("GFP", 50), ("RFP mCherry", 50), ("fluorescent reporter", 50),
    ("biosensor", 50), ("synthetic biology", 50), ("promoter library", 50),
    ("CRISPR", 50), ("gene circuit", 50), ("toggle switch", 50),
    ("inducible expression", 50), ("repressor", 50), ("activator", 50),
    ("kill switch", 30), ("quorum sensing", 30), ("T7 expression", 50),
    ("arabinose pBAD", 50), ("tetracycline", 50), ("IPTG lac", 50),
    ("terminator", 50), ("riboswitch", 30), ("optogenetics", 30),
    ("metabolic engineering", 50), ("plasmid backbone", 50),
    ("luciferase", 30), ("selection marker", 30),
]


# ---------------------------------------------------------------------------
# Ingestion runners
# ---------------------------------------------------------------------------


def ingest_uniprot_curated() -> list[BioPart]:
    from ingestion.ingest_uniprot import search_uniprot, _parse_entry

    seen: set[str] = set()
    parts: list[BioPart] = []

    for query, limit in UNIPROT_QUERIES:
        logger.info("UniProt: %s (limit %d, have %d)", query, limit, len(parts))
        try:
            entries = search_uniprot(query, limit=limit)
        except Exception:
            logger.warning("UniProt search failed: %s", query)
            continue

        for entry in entries:
            acc = entry.get("primaryAccession", "")
            if acc in seen or not acc:
                continue
            seen.add(acc)
            try:
                parts.append(_parse_entry(entry))
            except Exception:
                logger.warning("UniProt parse failed: %s", acc)

        time.sleep(0.5)

    logger.info("UniProt total: %d unique parts", len(parts))
    return parts


def ingest_igem_curated() -> list[BioPart]:
    from ingestion.ingest_igem import ingest_igem

    parts = list(ingest_igem(queries=IGEM_QUERIES, limit=5000))
    logger.info("iGEM total: %d unique parts", len(parts))
    return parts


def ingest_genbank_curated() -> list[BioPart]:
    from ingestion.ingest_genbank import search_genbank, fetch_genbank_record

    seen: set[str] = set()
    parts: list[BioPart] = []

    for query, limit in GENBANK_QUERIES:
        if len(parts) >= 1000:
            break
        logger.info("GenBank: %s (limit %d, have %d)", query, limit, len(parts))
        try:
            ids = search_genbank(query, limit=limit)
        except Exception:
            logger.warning("GenBank search failed: %s", query)
            continue

        for gid in ids:
            if len(parts) >= 1000:
                break
            if gid in seen:
                continue
            seen.add(gid)
            try:
                part = fetch_genbank_record(gid)
                if part:
                    parts.append(part)
            except Exception:
                logger.warning("GenBank fetch failed: %s", gid)
            time.sleep(0.4)  # respect NCBI rate limits

    logger.info("GenBank total: %d unique parts", len(parts))
    return parts


def ingest_addgene_curated() -> list[BioPart]:
    from ingestion.ingest_addgene import search_addgene, _to_biopart

    seen: set[str] = set()
    parts: list[BioPart] = []

    for query, limit in ADDGENE_QUERIES:
        if len(parts) >= 1000:
            break
        logger.info("Addgene: %s (limit %d, have %d)", query, limit, len(parts))
        try:
            entries = search_addgene(query, limit=limit)
        except Exception:
            logger.warning("Addgene search failed: %s", query)
            continue

        for entry in entries:
            eid = entry.get("id", "")
            if eid in seen or not eid:
                continue
            seen.add(eid)
            try:
                parts.append(_to_biopart(entry))
            except Exception:
                logger.warning("Addgene parse failed: %s", eid)

        time.sleep(1.0)  # be polite to Addgene

    logger.info("Addgene total: %d unique parts", len(parts))
    return parts


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

SOURCE_MAP = {
    "uniprot": ("UniProt (reviewed/curated)", ingest_uniprot_curated),
    "igem": ("iGEM Registry", ingest_igem_curated),
    "genbank": ("NCBI GenBank", ingest_genbank_curated),
    "addgene": ("Addgene (verified plasmids)", ingest_addgene_curated),
}


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Curated scrape of ~8,000 vetted biological parts → Qdrant + Knox",
    )
    parser.add_argument(
        "--sources", nargs="+", default=list(SOURCE_MAP.keys()),
        choices=list(SOURCE_MAP.keys()),
        help="Which sources to scrape (default: all)",
    )
    parser.add_argument("--skip-knox", action="store_true", help="Scrape into Qdrant only, don't upload to Knox")
    parser.add_argument("--knox-url", default="http://localhost:8080", help="Knox server URL")
    parser.add_argument("--knox-space", default="curated_parts", help="Knox design space name")
    args = parser.parse_args()

    from database.vector_store import get_vector_store
    store = get_vector_store()

    all_parts: list[BioPart] = []

    for source_key in args.sources:
        label, fn = SOURCE_MAP[source_key]
        print(f"\n{'='*60}")
        print(f"  {label}")
        print(f"{'='*60}")
        t0 = time.time()
        try:
            parts = fn()
            count = store.upsert_parts(parts)
            elapsed = time.time() - t0
            print(f"  -> {count} parts ingested in {elapsed:.0f}s")
            all_parts.extend(parts)
        except Exception as e:
            logger.exception("Failed: %s", label)
            print(f"  -> FAILED: {e}")

    print(f"\n{'='*60}")
    print(f"  TOTAL: {len(all_parts)} parts in Qdrant vector store")
    print(f"  Vector store count: {store.count()}")
    print(f"{'='*60}")

    if args.skip_knox:
        print("\n--skip-knox set, not uploading to Knox.")
        return 0

    # Sync to Knox
    print(f"\nSyncing {len(all_parts)} parts to Knox at {args.knox_url}...")

    # Add project scripts to path for sync_parts_to_knox
    scripts_dir = str(Path(__file__).resolve().parent)
    if scripts_dir not in sys.path:
        sys.path.insert(0, scripts_dir)

    from sync_parts_to_knox import generate_knox_csvs, generate_descriptions_sidecar, upload_to_knox

    part_dicts = [p.model_dump() for p in all_parts]
    comp_csv, design_csv = generate_knox_csvs(part_dicts)
    sidecar_json = generate_descriptions_sidecar(part_dicts)

    result = upload_to_knox(
        comp_csv=comp_csv,
        design_csv=design_csv,
        space_prefix=args.knox_space,
        knox_url=args.knox_url,
        use_or=True,
    )

    if result["success"]:
        print(f"  -> Uploaded to Knox as '{args.knox_space}'")
        sidecar_path = Path(f"{args.knox_space}_descriptions.json")
        sidecar_path.write_text(sidecar_json)
        print(f"  -> Descriptions: {sidecar_path}")
        return 0
    else:
        print(f"  -> Knox upload FAILED: {result['message']}")
        print("  Parts are still in Qdrant. Retry Knox upload with:")
        print(f"  python scripts/sync_parts_to_knox.py --query '' --knox-url {args.knox_url}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
