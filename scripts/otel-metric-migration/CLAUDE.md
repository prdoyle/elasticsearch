# OTel metric migration – design (for humans and AI)

This directory is **phase 1** of an OTel metric migration: a **read-only** reporter that finds Kibana saved objects referencing legacy metric names across many clusters. It does not modify saved objects or indices.

**Design goal:** The script should run **unattended to the greatest extent possible in a secure fashion** (e.g. persist credentials in gitignored locations, avoid echoing secrets, support API keys so browser interaction is only needed when necessary).

## Entry point

- **`report_metric_references.py`** – The only script you run. It has the shebang and `if __name__ == "__main__"`. All other `.py` files here are helper libraries.

## Design: logic vs I/O and unit testing

We separate **pure logic** from **I/O and side effects** so that business rules and branching can be **unit-tested to the greatest possible extent** without touching the network, filesystem, or browser.

- **Pure logic** lives in dedicated modules (`core`, `report_builder`, and the testable parts of `auth`). These functions take in-memory inputs (strings, dicts, lists) and return in-memory outputs. They do not read files, open sockets, or launch browsers. All such code is covered by unit tests in `tests/test_*.py` with no mocks of real services: we just call the functions with crafted data and assert on return values.
- **I/O and side effects** are confined to thin wrappers or the main script: file read/write, HTTP calls, Playwright, `webbrowser.open`, `getpass`, etc. The main orchestration code (`report_metric_references.run`, `process_cluster`) receives **injectable** dependencies (e.g. `get_credentials_fn`, `export_fn`) so that tests can supply fake credential and export behavior without doing real I/O. Where we cannot easily inject (e.g. `getpass`, `webbrowser.open`), we mock them in tests so that the 401 + paste-key flow and similar paths are still testable.
- **Result:** The vast majority of behavior—URL normalization, config parsing, scan logic, stop policy, credential validation, cache TTL—is covered by fast, deterministic unit tests. Only the thin I/O layers and integration-style flows are left to manual or integration testing.

| Module | Role | Pure (tested) | I/O (not unit tested) |
|--------|------|----------------|------------------------|
| **core** | Scan saved objects for metric names; build report structure; normalize URLs; parse metrics config. | Yes – all of it. | None. |
| **report_builder** | Parse cluster list and credentials file content; turn a stream of saved objects into one cluster entry; apply “stop after N consecutive failures” over results. | Yes – all of it. | None. |
| **auth** | Resolve credentials from API key map only. Build credential dicts; cluster slug (for tests). | `cluster_slug`, `get_credentials_from_api_key`, `get_credentials`. | None. |
| **kibana_client** | POST Kibana saved-objects export, stream NDJSON, yield parsed objects. | None. | HTTP only. |
| **report_metric_references** | CLI, load config files, loop over clusters, call auth and export, collect results, write report and errors JSON. | None. | File read/write; delegates to auth and kibana_client. |

## Flow

**Startup**

- Load the cluster list and metrics config from the files given on the CLI (report_builder, core).
- Load API keys from `api-keys.json` in this directory if the file exists (report_builder). If it doesn’t exist or is empty, the script will prompt for keys when needed.

**Per cluster**

For each cluster URL:

1. Get an API key for that cluster from the in-memory map (auth). If none is found, the script can open the API key page and prompt the user to paste one, then save it to `api-keys.json` and retry.
2. Export all saved objects from the cluster (kibana_client).
3. Scan each object for references to the legacy metric names (core).
4. Build one “cluster entry” for the report: cluster URL plus the list of objects that reference those metrics (report_builder.objects_to_cluster_entry).

**Finish**

- Decide whether we hit the “stop after N consecutive failures” limit (report_builder.apply_stop_policy).
- Build the full report structure and write `metric_references_report.json` and, if there were failures, `metric_report_errors.json` (core, report_metric_references).

## Running and testing

- **Run:** From this directory: `python report_metric_references.py --clusters ... --metrics ...` (see README for full options).
- **Tests:** `pytest tests/ -v`. Tests use in-memory data only; no network, no browser, no real cache files. conftest.py adds this directory to `sys.path` so `import core`, `import auth`, etc. work.

## Conventions

- **Flat layout:** No Python package (no `otel_migration/`). Script and helpers are siblings; run and test from this directory.
- **Public API:** Functions that are part of the supported, testable API have no leading underscore (e.g. `cluster_slug`, `get_credentials_from_api_key`). Leading underscore = internal to the module.
- **ES URL:** For future phases, Elasticsearch URL is derived from Kibana URL by replacing `.kb.` with `.es.` in the host (see `core.derive_es_url`).

## Phase 2 (future)

A later step will consume the report JSON to add runtime fields and update dashboards and alerting rules. This tool only produces the report.
