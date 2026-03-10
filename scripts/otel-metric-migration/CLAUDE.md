# OTel metric migration – design (for humans and AI)

This directory is **phase 1** of an OTel metric migration: a **read-only** reporter that finds Kibana saved objects referencing legacy metric names across many clusters. It does not modify saved objects or indices.

## Entry point

- **`report_metric_references.py`** – The only script you run. It has the shebang and `if __name__ == "__main__"`. All other `.py` files here are helper libraries.

## Design: logic vs I/O

Logic that can be unit tested is separated from I/O (files, network, browser). Pure functions live in dedicated modules and take in-memory data; the main script and auth/kibana_client do the I/O and call into those functions.

| Module | Role | Pure (tested) | I/O (not unit tested) |
|--------|------|----------------|------------------------|
| **core** | Scan saved objects for metric names; build report structure; normalize URLs; parse metrics config. | Yes – all of it. | None. |
| **report_logic** | Parse cluster list and credentials file content; turn a stream of saved objects into one cluster entry; apply “stop after N consecutive failures” over results. | Yes – all of it. | None. |
| **auth** | Resolve credentials: API key map → cache (file) → browser (Playwright). Build credential dicts; validate cache payload and TTL; cluster slug for cache filenames. | `cluster_slug`, `parse_and_validate_cached_credentials`, `get_credentials_from_api_key`, `get_credentials_from_cookies`. | `load_cached_credentials`, `save_cached_credentials`, `invalidate_cached_credentials`, `_get_credentials_via_browser`, `get_credentials`. |
| **kibana_client** | POST Kibana saved-objects export, stream NDJSON, yield parsed objects. | None. | HTTP only. |
| **report_metric_references** | CLI, load config files, loop over clusters, call auth and export, collect results, write report and errors JSON. | None. | File read/write; delegates to auth and kibana_client. |

## Flow

1. Load cluster URLs and metrics config (report_logic + core); optionally load credentials map (report_logic).
2. For each cluster: get credentials (auth: map → cache → browser); export saved objects (kibana_client); scan each object for old metric names (core); build cluster entry (report_logic.objects_to_cluster_entry).
3. Apply stop policy on results (report_logic.apply_stop_policy); build full report (core); write report and errors JSON.

## Running and testing

- **Run:** From this directory: `python report_metric_references.py --clusters ... --metrics ...` (see README for full options).
- **Tests:** `pytest tests/ -v`. Tests use in-memory data only; no network, no browser, no real cache files. conftest.py adds this directory to `sys.path` so `import core`, `import auth`, etc. work.

## Conventions

- **Flat layout:** No Python package (no `otel_migration/`). Script and helpers are siblings; run and test from this directory.
- **Public API:** Functions that are part of the supported, testable API have no leading underscore (e.g. `cluster_slug`, `parse_and_validate_cached_credentials`). Leading underscore = internal to the module.
- **ES URL:** For future phases, Elasticsearch URL is derived from Kibana URL by replacing `.kb.` with `.es.` in the host (see `core.derive_es_url`).

## Phase 2 (future)

A later step will consume the report JSON to add runtime fields and update dashboards and alerting rules. This tool only produces the report.
