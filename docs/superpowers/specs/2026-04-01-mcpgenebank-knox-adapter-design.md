# MCPGeneBank → Knox Parts Adapter

## Summary

A standalone Python script (`scripts/sync_parts_to_knox.py`) that fetches biological parts from MCPGeneBank's vector store, converts them into Knox-compatible SBOL or CSV format with full metadata preserved, and uploads them to Knox via its REST API.

This is the first integration bridge between the two systems. It is a one-directional parts pipeline — it does not apply GOLDBAR constraints or orchestrate design. Users apply rules through Knox's UI or AI chat after parts are loaded.

**TODO (future):** Expose this as an endpoint in MCPGeneBank's FastAPI server or as a Knox AI tool so the LLM agent can trigger parts sync automatically.

## Requirements

- Parts fetched from MCPGeneBank MUST retain: part_id, common name, function, description, tags, organism, source_database, sequence, and references.
- Knox must be able to display and reason about this metadata through its AI chat and graph visualization.
- The script must work with Knox running via Docker (`localhost:8080`) or standalone.
- The script must work with MCPGeneBank's vector store in any mode (remote Qdrant, local on-disk, in-memory with demo seed data).

## Data Mapping

### BioPart → SBOL (primary path)

Each BioPart maps to an SBOL3 Component:

| BioPart field | SBOL3 representation |
|---|---|
| `part_id` | Component.displayId |
| `name` | Component.name |
| `type` | Component.type (mapped to SO terms: SO:0000167 for promoter, SO:0000316 for CDS, etc.) |
| `sequence` | Sequence object linked to Component |
| `description` | Component.description |
| `function` | Appended to description |
| `tags` | Custom annotations on Component |
| `organism` | Custom annotation |
| `source_database` | Custom annotation |
| `references` | Custom annotation (list of URLs) |

Knox's `SBOLConversion.java` imports SBOL documents and creates design space graphs. Component names and identifiers carry through to Knox's edge labels.

### BioPart → CSV (fallback path)

Knox CSV format: columns = part roles, rows = part IDs.

To preserve metadata in the flat CSV format, componentIDs are encoded as:

```
BBa_K1031907|Pars_Arsenic_Sensing_Promoter|arsenic,metal_sensing,biosensor
```

Format: `{part_id}|{name_underscored}|{comma_separated_tags}`

This is a lossy encoding (no full description or sequence) but Knox's AI tools return these strings to the LLM, so names and tags remain visible for reasoning.

## Architecture

```
┌─────────────────────────┐
│  sync_parts_to_knox.py  │
├─────────────────────────┤
│                         │
│  1. PartsFetcher        │─── imports from MCPGeneBank ───▶ list[BioPart]
│     - vector store      │
│     - or direct ingest  │
│                         │
│  2. SbolFormatter       │─── BioPart → sbol3.Document
│     (primary)           │
│                         │
│  3. CsvFormatter        │─── BioPart → CSV string
│     (fallback)          │
│                         │
│  4. KnoxUploader        │─── POST to Knox REST API
│     - /sbol/import      │
│     - /import/csv       │
└─────────────────────────┘
```

### Module breakdown

#### 1. PartsFetcher

Retrieves parts from MCPGeneBank. Two modes:

- **Vector store mode** (default): Imports MCPGeneBank's `database.vector_store.get_vector_store()` and calls `search()` with the user's query. Groups results by `type`.
- **Direct ingest mode** (`--fresh-ingest`): Runs MCPGeneBank's ingestion pipeline (`ingest_igem`, `ingest_genbank`, etc.) to fetch fresh parts from external databases, bypassing the vector store. Useful for first-time setup before Qdrant is populated.

Returns: `dict[str, list[BioPart]]` — parts grouped by type (promoter, reporter, regulator, etc.)

#### 2. SbolFormatter

Converts a list of BioParts into an SBOL3 Document.

- Each BioPart becomes an `sbol3.Component` with appropriate SO type URI
- Sequence attached as `sbol3.Sequence` with IUPAC DNA encoding
- Metadata stored as custom annotations (organism, source, tags, function)
- All parts grouped under a single SBOL Document for bulk import

Type mapping:

| BioPart.type | SO term | SO URI |
|---|---|---|
| promoter | promoter | SO:0000167 |
| rbs | ribosome_entry_site | SO:0000139 |
| cds / coding | CDS | SO:0000316 |
| terminator | terminator | SO:0000141 |
| reporter | CDS | SO:0000316 (with reporter annotation) |
| regulator | CDS | SO:0000316 (with regulator annotation) |
| enzyme | CDS | SO:0000316 (with enzyme annotation) |
| other | engineered_region | SO:0000804 |

#### 3. CsvFormatter

Fallback for when SBOL import is problematic.

- Builds a CSV where each column is a part role
- Each row is one part's encoded ID: `{part_id}|{name}|{tags}`
- Columns are padded with empty strings to make a rectangular matrix (Knox expects uniform rows)

Example output:
```csv
promoter,rbs,regulator,reporter,terminator
BBa_K1031907|Pars_Arsenic_Sensing_Promoter|arsenic,BBa_B0034|RBS_B0034|translation,BBa_K1031311|ArsR|arsenic,BBa_E0040|GFP|fluorescence,BBa_B0015|Double_Terminator|
BBa_R0040|PLtet|tetracycline,,,BBa_E1010|mRFP1|red_fluorescence,
```

#### 4. KnoxUploader

HTTP client that sends formatted data to Knox.

- For SBOL: `POST /sbol/import` with the SBOL XML document and `targetSpaceID` parameter
- For CSV: `POST /import/csv` with the CSV file and `outputSpacePrefix` parameter
- Verifies upload succeeded by calling `GET /designSpace/list` and confirming the new space exists
- Reports: number of parts uploaded, design space ID created, any errors

Uses `httpx` (already a MCPGeneBank dependency) for HTTP calls.

## CLI Interface

```bash
# Basic usage — search MCPGeneBank for arsenic-related parts, push to Knox as SBOL
python scripts/sync_parts_to_knox.py \
  --query "arsenic biosensor" \
  --limit 50 \
  --knox-url http://localhost:8080 \
  --space-id "arsenic_parts"

# Multiple queries to build a broader parts catalog
python scripts/sync_parts_to_knox.py \
  --query "arsenic" "mercury" "GFP" "tetracycline" \
  --limit 30 \
  --knox-url http://localhost:8080 \
  --space-id "biosensor_catalog"

# CSV fallback
python scripts/sync_parts_to_knox.py \
  --query "arsenic biosensor" \
  --format csv \
  --knox-url http://localhost:8080 \
  --space-id "arsenic_parts"

# Fresh ingest from external databases (no Qdrant needed)
python scripts/sync_parts_to_knox.py \
  --fresh-ingest \
  --sources igem genbank \
  --query "arsenic" \
  --limit 20 \
  --knox-url http://localhost:8080 \
  --space-id "arsenic_fresh"

# Dry run — generate SBOL/CSV file without uploading
python scripts/sync_parts_to_knox.py \
  --query "arsenic biosensor" \
  --dry-run \
  --output parts_export.xml
```

### Arguments

| Argument | Default | Description |
|---|---|---|
| `--query` | required | One or more search queries |
| `--limit` | 50 | Max parts per query |
| `--knox-url` | `http://localhost:8080` | Knox server URL |
| `--space-id` | auto-generated from query | Knox design space ID for the import |
| `--format` | `sbol` | Export format: `sbol` or `csv` |
| `--fresh-ingest` | false | Bypass vector store, ingest directly from external DBs |
| `--sources` | all | Which databases for fresh ingest: igem, genbank, uniprot, addgene |
| `--dry-run` | false | Generate file without uploading to Knox |
| `--output` | none | Write generated SBOL/CSV to file (implies dry-run) |
| `--mcpgenebank-dir` | `../MCPGeneBank/bio-circuit-ai` | Path to MCPGeneBank source |
| `--group-id` | none | Knox group ID to assign to the created design space |

## Dependencies

The script lives at the project root level (`scripts/`) and imports from MCPGeneBank by adding its directory to `sys.path`. No new pip packages required — uses:

- `sbol3` (from generative-syn-bio's dependencies)
- `httpx` (from MCPGeneBank's dependencies)
- MCPGeneBank's `database.vector_store`, `models.part`, `ingestion.*`, `tools.search_parts`

## Error Handling

- If Knox is unreachable: fail with clear message suggesting `docker-compose up`
- If MCPGeneBank vector store is empty: suggest running `scrape_300.py` or use `--fresh-ingest`
- If SBOL import fails: automatically retry with CSV fallback and warn user
- If no parts match query: report zero results, suggest broader query terms

## Testing Strategy

- Unit test the formatters (SbolFormatter, CsvFormatter) with fixture BioParts
- Integration test requires both Knox (Docker) and MCPGeneBank (vector store) running
- Dry-run mode enables testing the full pipeline without Knox

## What This Does NOT Do

- Does not apply GOLDBAR constraints — that's a separate user action in Knox
- Does not create circuit designs — it only loads the parts catalog
- Does not score or rank parts — Knox/GOLDBAR handles that
- Does not call Evo 2 — that's a separate integration (generative-syn-bio)
