# OTel metric migration – reporting (phase 1)

This script produces a **read-only report** of Kibana saved objects that reference legacy metric names across one or more clusters. It is intended for planning an OpenTelemetry metric rename migration. It does not modify any saved objects or index mappings.

## Inputs

- **Cluster list:** A file with one Kibana base URL per line (e.g. `https://platform-metrics.kb.af-south-1.aws.elastic-cloud.com`). See `clusters.txt.example`.
- **Metrics config:** A JSON file with an array of `{"old": "<metric_name>", "new": "<metric_name>"}`. The script searches for the `old` names. See `metrics_config.json.example`.

## Setup (Python virtual environment)

The script uses a **virtual environment** (venv) so its dependencies are isolated from the rest of your system. From this directory (`scripts/otel-metric-migration/`):

1. **Create the venv** (only needed once, or if `.venv` doesn't exist):
   ```bash
   python3 -m venv .venv
   ```

2. **Activate the venv** so that `python` and `pip` in this terminal use the environment inside `.venv`. You must do this in every new terminal before running the script or tests:
   ```bash
   source .venv/bin/activate
   ```
   Your prompt may show `(.venv)` to indicate the venv is active.

## Running the script

From this directory, with the venv activated (see Setup above):

```bash
source .venv/bin/activate   # do this first in each new terminal
pip install -r requirements.txt

python report_metric_references.py \
  --clusters clusters.txt \
  --metrics metrics_config.json \
  --output-dir ./out
```

Options:

- `--output-dir ./out` – Directory for report and errors JSON (default: `./out`).

## Authentication

- **API keys:** The script uses API keys only. Keys are stored in `api-keys.json` in this directory (gitignored). When the script needs an API key for a cluster and none is found, or when a request returns 401, it opens the Kibana API key management page in your browser and prompts you to paste a newly created API key (input is not echoed). If you paste a key and the export succeeds, the key is saved to `api-keys.json` so future runs use it without prompting.

## Output

- **Report:** `./out/<timestamp>/metric_references_report.json` – Lists each cluster and, for each, saved objects that reference any of the old metric names, with type, id, title, and where each metric appears (path and snippet). Includes `metrics_config_used` and `generated_at`.
- **Errors:** If any cluster failed, `./out/<timestamp>/metric_report_errors.json` – One entry per failure with `cluster`, `phase` (auth or export), and `error`. Includes `consecutive_failure_count`.

The script stops after 5 consecutive cluster failures and exits with code 1; the report still contains all successfully processed clusters.

## Elasticsearch URL

For future phases (runtime fields, etc.), the Elasticsearch URL is derived from the Kibana URL by replacing `.kb.` with `.es.` in the host (e.g. `*.kb.*.aws.elastic-cloud.com` → `*.es.*.aws.elastic-cloud.com`).

## Running tests

From this directory, with the venv activated (see Setup above):

```bash
source .venv/bin/activate   # do this first in each new terminal
pip install -r requirements-dev.txt
python -m pytest tests/ -v
```

Tests cover the core scanning and report-building logic only (no HTTP or browser).

## Phase 2 (future)

A later repair phase will consume the report to add runtime fields and update dashboards and alerting rules. This script only produces the report.
