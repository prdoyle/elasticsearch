#!/usr/bin/env python3
# Licensed to Elasticsearch B.V. under one or more contributor
# license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright
# ownership. Elasticsearch B.V. licenses this file to you under
# the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""
CLI to report saved objects that reference legacy metric names across Kibana clusters.
Read-only: exports saved objects and writes a JSON report. No modifications to clusters.
"""

from __future__ import annotations

import argparse
import getpass
import json
import sys
import webbrowser
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import requests

import auth
import core
import kibana_client
import report_builder


def load_cluster_list(path: str) -> list[str]:
    """Load cluster URLs from a file (one per line), normalized and deduplicated."""
    return report_builder.parse_cluster_list(Path(path).read_text())


def load_metrics_config(path: str) -> list[dict[str, str]]:
    """Load and validate metrics config JSON."""
    data = json.loads(Path(path).read_text())
    return core.parse_metrics_config(data)


def load_credentials_map(path: str) -> dict[str, str]:
    """Load API keys file: JSON object mapping Kibana URL -> API key."""
    data = json.loads(Path(path).read_text())
    return report_builder.parse_credentials_map(data)


def _api_key_page_url(cluster_url: str) -> str:
    """Return the Kibana API key management page URL for the given cluster."""
    base_url = core.normalize_kibana_url(cluster_url).rstrip("/")
    return f"{base_url}/app/management/security/api_keys"


def _collect_api_key_via_browser(api_key_page_url: str) -> str:
    """Open the API key page in the browser, prompt for pasted key, return it or empty string.
    When not in an interactive terminal, prints a hint and returns "" without prompting.
    """
    if not sys.stdin.isatty():
        print(
            "Re-run in an interactive terminal to paste an API key.",
            file=sys.stderr,
        )
        return ""
    webbrowser.open(api_key_page_url)
    print(
        "Create an API key in the opened browser, then paste it here (or press Enter to skip this cluster):",
        file=sys.stderr,
    )
    return getpass.getpass("API key: ").strip()


def _save_credentials_to_file(api_keys_path: str | Path, cluster_url: str, api_key: str) -> None:
    """Read API keys file (if exists), add or update cluster_url -> api_key, write back."""
    path = Path(api_keys_path)
    if path.exists():
        data = json.loads(path.read_text())
        if not isinstance(data, dict):
            data = {}
    else:
        data = {}
    url = core.normalize_kibana_url(cluster_url)
    data[url] = api_key
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2))


def process_cluster(
    cluster_url: str,
    old_metrics: list[str],
    get_credentials_fn: Callable[[str], dict[str, Any]],
    export_fn: Callable[[str, dict[str, Any]], Any],
    api_keys_path: str | Path,
    credentials_map: dict[str, str],
    collect_api_key_fn: Callable[[str], str] = _collect_api_key_via_browser,
) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    """
    Export saved objects from one cluster, scan for old metrics, return (cluster_entry, errors).
    Returns None for cluster_entry on failure; errors list has one entry per failure.
    On 401 or missing API key, may prompt for pasted key via collect_api_key_fn; if provided and
    export succeeds, key is saved to api_keys_path and credentials_map is updated.
    """
    api_key_page_url = _api_key_page_url(cluster_url)

    def _prompt_and_save_then_retry_export(retry_exception: Exception):
        api_key = collect_api_key_fn(api_key_page_url)
        if not api_key:
            return None, [{"cluster": cluster_url, "phase": "export", "error": str(retry_exception)}]
        creds = auth.get_credentials_from_api_key(api_key)
        try:
            objects = export_fn(cluster_url, creds)
            entry = report_builder.objects_to_cluster_entry(cluster_url, objects, old_metrics)
            url = core.normalize_kibana_url(cluster_url)
            credentials_map[url] = api_key
            _save_credentials_to_file(api_keys_path, cluster_url, api_key)
            return entry, []
        except Exception as third_e:
            return None, [{"cluster": cluster_url, "phase": "export", "error": str(third_e)}]

    try:
        creds = get_credentials_fn(cluster_url)
    except RuntimeError as e:
        api_key = collect_api_key_fn(api_key_page_url)
        if not api_key:
            return None, [{"cluster": cluster_url, "phase": "auth", "error": str(e)}]
        url = core.normalize_kibana_url(cluster_url)
        credentials_map[url] = api_key
        _save_credentials_to_file(api_keys_path, cluster_url, api_key)
        try:
            creds = get_credentials_fn(cluster_url)
        except Exception as retry_e:
            return None, [{"cluster": cluster_url, "phase": "auth", "error": str(retry_e)}]

    try:
        objects = export_fn(cluster_url, creds)
        entry = report_builder.objects_to_cluster_entry(cluster_url, objects, old_metrics)
        return entry, []
    except requests.HTTPError as e:
        if e.response is not None and e.response.status_code == 401:
            return _prompt_and_save_then_retry_export(e)
        return None, [{"cluster": cluster_url, "phase": "export", "error": str(e)}]
    except Exception as e:
        return None, [{"cluster": cluster_url, "phase": "export", "error": str(e)}]


def run(
    clusters: list[str],
    metrics_config: list[dict[str, str]],
    output_dir: str,
    credentials_map: dict[str, str],
    api_keys_path: str | Path,
    collect_api_key_fn: Callable[[str], str] = _collect_api_key_via_browser,
    max_consecutive_failures: int = 5,
) -> int:
    """
    Process each cluster, write report and errors JSON. Returns 0 on success, non-zero if
    we hit max_consecutive_failures or could not write outputs.
    """
    old_metrics = core.get_old_metric_names(metrics_config)

    def get_creds(url: str) -> dict[str, Any]:
        return auth.get_credentials(url, credentials_map)

    def export(url: str, creds: dict[str, Any]):
        return kibana_client.export_saved_objects(url, creds)

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_dir = Path(output_dir) / timestamp
    report_path = run_dir / "metric_references_report.json"
    errors_path = run_dir / "metric_report_errors.json"

    results = []
    consecutive_failures = 0
    for cluster_url in clusters:
        result = process_cluster(
            cluster_url,
            old_metrics,
            get_creds,
            export,
            api_keys_path,
            credentials_map,
            collect_api_key_fn=collect_api_key_fn,
        )
        results.append(result)
        entry, errs = result
        if entry is not None:
            consecutive_failures = 0
        if errs:
            consecutive_failures += 1
            if consecutive_failures >= max_consecutive_failures:
                break

    cluster_entries, all_errors, stopped_early = report_builder.apply_stop_policy(
        results, max_consecutive_failures
    )

    run_dir.mkdir(parents=True, exist_ok=True)
    report = core.build_full_report(cluster_entries, metrics_config)
    report_path.write_text(json.dumps(report, indent=2))

    if all_errors:
        errors_path.write_text(json.dumps(all_errors, indent=2))
    return 1 if stopped_early else 0


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    api_keys_path = script_dir / "api-keys.json"

    parser = argparse.ArgumentParser(
        description="Report Kibana saved objects that reference legacy metric names (read-only)."
    )
    parser.add_argument(
        "--clusters",
        required=True,
        help="Path to file with one Kibana base URL per line",
    )
    parser.add_argument(
        "--metrics",
        required=True,
        help="Path to JSON file with metrics mapping: [{\"old\": \"...\", \"new\": \"...\"}, ...]",
    )
    parser.add_argument(
        "--output-dir",
        default="./out",
        help="Directory for report and errors JSON (default: ./out)",
    )
    args = parser.parse_args()

    try:
        clusters = load_cluster_list(args.clusters)
        metrics_config = load_metrics_config(args.metrics)
    except Exception as e:
        print(f"Error loading config: {e}", file=sys.stderr)
        return 1

    credentials_map = {}
    if api_keys_path.exists():
        try:
            credentials_map = load_credentials_map(str(api_keys_path))
        except Exception as e:
            print(f"Error loading API keys file: {e}", file=sys.stderr)
            return 1

    return run(
        clusters=clusters,
        metrics_config=metrics_config,
        output_dir=args.output_dir,
        credentials_map=credentials_map,
        api_keys_path=api_keys_path,
    )


if __name__ == "__main__":
    sys.exit(main())
