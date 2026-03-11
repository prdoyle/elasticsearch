# OTel metric migration – design (for humans and AI)

This directory is **phase 1** of an OTel metric migration: a **read-only** reporter that finds Kibana saved objects referencing legacy metric names across many clusters. It does not modify saved objects or indices.

**Design goal:** The script should run **unattended to the greatest extent possible in a secure fashion** (e.g. persist credentials in gitignored locations, avoid echoing secrets, support API keys so browser interaction is only needed when necessary).

## Entry point

- **`omm.py`** and the **`omm`** bash wrapper – Preferred way to run a pipeline from a YAML preset (e.g. `omm qa-report.yaml`). The wrapper runs `omm.py` in the venv and passes arguments through.
- **`report_metric_references.py`** – Can still be run directly for the legacy CLI (`--clusters`, `--metrics`). All other `.py` files are helper libraries.

## Design: logic vs I/O and unit testing

We separate **pure logic** from **I/O and side effects** so that business rules can be unit-tested without touching the network, filesystem, or browser.

- **Pure logic** lives in `core`, `report_builder`, `pipeline`, and the testable parts of `auth`: in-memory in, in-memory out; no file/socket/browser access. All of it is covered by unit tests in `tests/test_*.py` with no mocks—call with crafted data, assert on return values.
- **I/O and side effects** stay in thin wrappers and the main script. Orchestration (`report_metric_references.run`, `process_cluster`) uses **injectable** dependencies (`get_credentials_fn`, `export_fn`, `collect_api_key_fn`) so tests can supply fakes. **collect_api_key_fn** decides whether to prompt: `_collect_api_key_via_browser` checks `sys.stdin.isatty()`, returns `""` with a hint when non-interactive, and opens the browser for a pasted key when interactive (no separate `interactive` parameter).
- **Orchestration** (load file → parse → loop and dispatch) is not unit tested. We extract testable logic into pure functions and test those. The wiring is kept simple with minimal conditionals so it either works or fails as a whole, which makes it easy to verify with manual or integration tests.
- **Behavioural change without unit test changes = test gap.** Add or adjust tests so future changes to that behaviour show up in the suite; then every behavioural change is manifest in pull requests. Exception: I/O and orchestration (see above) are not unit tested.
- **Result:** The vast majority of behaviour—URL normalization, config parsing, scan logic, stop policy, credential validation, cache TTL, pipeline and preset parsing, step output path—is covered by fast, deterministic unit tests. I/O and integration-style flows are left to manual or integration testing.

| Module | Role | Pure (tested) | I/O (not unit tested) |
|--------|------|----------------|------------------------|
| **core** | Scan saved objects for metric names; build report structure; normalize URLs; parse metrics config. | Yes – all of it. | None. |
| **report_builder** | Parse cluster list and credentials file content; turn a stream of saved objects into one cluster entry; apply “stop after N consecutive failures” over results. | Yes – all of it. | None. |
| **auth** | Resolve credentials from API key map only. Build credential dicts; cluster slug (for tests). | `cluster_slug`, `get_credentials_from_api_key`, `get_credentials`. | None. |
| **kibana_client** | POST Kibana saved-objects export, stream NDJSON, yield parsed objects. | None. | HTTP only. |
| **pipeline** | Parse preset YAML into (step_name, verb, config) steps; validate report config and step names; resolve preset filename; step_output_dir (path for step output). | Yes – all of it. | None. |
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

- **Agents must not run against real clusters to verify behavior.** Scripts issue HTTP requests to Kibana/Elasticsearch. Use `pytest tests/ -v` instead; manual runs are for operators hitting real clusters with appropriate config.
  - Humans: the responsibility is still yours. Agents gonna agent. Test without network, or without VPN, or against QA. Use your judgement.
- **Run:** `omm <preset>.yaml` (e.g. `omm qa-report`) or `python report_metric_references.py --clusters ... --metrics ...` (see README).
- **Tests:** `pytest tests/ -v`; in-memory only, conftest.py adds this directory to `sys.path`.

## Conventions

- **Flat layout:** No Python package (no `otel_migration/`). Script and helpers are siblings; run and test from this directory.
- **Public API:** Functions that are part of the supported, testable API have no leading underscore (e.g. `cluster_slug`, `get_credentials_from_api_key`). Leading underscore = internal to the module.
- **ES URL:** For future phases, Elasticsearch URL is derived from Kibana URL by replacing `.kb.` with `.es.` in the host (see `core.derive_es_url`).

## Phase 2 (future)

A later step will consume the report JSON to add runtime fields and update dashboards and alerting rules. This tool only produces the report.
