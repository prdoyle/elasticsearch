# OTel metric migration – reporting (phase 1)

This script produces a **read-only report** of Kibana saved objects that reference legacy metric names across one or more clusters. It is intended for planning an OpenTelemetry metric rename migration. It does not modify any saved objects or index mappings.

## Inputs

- **Cluster list:** A file with one Kibana base URL per line (e.g. `https://platform-metrics.kb.af-south-1.aws.elastic-cloud.com`). See `clusters.txt.example`.
- **Metrics config:** A JSON file with an array of `{"old": "<metric_name>", "new": "<metric_name>"}`. The script searches for the `old` names. See `metrics_config.json.example`.

## Running the script

From this directory (`scripts/otel-metric-migration/`):

```bash
pip install -r requirements.txt
# If using browser auth, install Playwright browser:
# playwright install chromium

python report_metric_references.py \
  --clusters clusters.txt \
  --metrics metrics_config.json \
  --output-dir ./out
```

Options:

- `--credentials credentials.json` – Optional. JSON object mapping Kibana URL to API key. If provided for a cluster, the script uses the API key and skips browser auth for that cluster.
- `--cache-dir .cache` – Directory for credential cache (default: `.cache`). Cached sessions are reused for 45 minutes.
- `--no-browser` – Do not open a browser. Use only credentials from `--credentials` and cache; the script will fail if no valid credential is available for a cluster.

## Authentication

- **API key:** Create an API key in Kibana (after logging in with Okta), then add the cluster URL and key to a JSON file and pass it with `--credentials`. This avoids opening a browser per cluster.
- **Browser (Okta):** If you do not provide an API key for a cluster, the script opens a browser to the Kibana URL. Log in via Okta; the script then captures the session and uses it for the export request. The session is cached for 45 minutes.
- If a request returns 401, the script invalidates the cache for that cluster and (if not `--no-browser`) will prompt for browser login again on the next run.

## Output

- **Report:** `./out/<timestamp>/metric_references_report.json` – Lists each cluster and, for each, saved objects that reference any of the old metric names, with type, id, title, and where each metric appears (path and snippet). Includes `metrics_config_used` and `generated_at`.
- **Errors:** If any cluster failed, `./out/<timestamp>/metric_report_errors.json` – One entry per failure with `cluster`, `phase` (auth or export), and `error`. Includes `consecutive_failure_count`.

The script stops after 5 consecutive cluster failures and exits with code 1; the report still contains all successfully processed clusters.

## Elasticsearch URL

For future phases (runtime fields, etc.), the Elasticsearch URL is derived from the Kibana URL by replacing `.kb.` with `.es.` in the host (e.g. `*.kb.*.aws.elastic-cloud.com` → `*.es.*.aws.elastic-cloud.com`).

## Running tests

From this directory:

```bash
pip install -r requirements-dev.txt
pytest tests/ -v
```

Tests cover the core scanning and report-building logic only (no HTTP or browser).

## Phase 2 (future)

A later repair phase will consume the report to add runtime fields and update dashboards and alerting rules. This script only produces the report.
