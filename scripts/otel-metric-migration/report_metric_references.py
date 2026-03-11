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
import json
import sys
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
    """Load optional credentials file: JSON object mapping Kibana URL -> API key."""
    data = json.loads(Path(path).read_text())
    return report_builder.parse_credentials_map(data)


def process_cluster(
    cluster_url: str,
    old_metrics: list[str],
    get_credentials_fn: Callable[[str], dict[str, Any]],
    export_fn: Callable[[str, dict[str, Any]], Any],
    cache_dir: str | None,
    use_browser: bool,
    credentials_map: dict[str, str] | None,
) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    """
    Export saved objects from one cluster, scan for old metrics, return (cluster_entry, errors).
    Returns None for cluster_entry on failure; errors list has one entry per failure.
    """
    errors = []
    try:
        creds = get_credentials_fn(cluster_url)
    except Exception as e:
        return None, [{"cluster": cluster_url, "phase": "auth", "error": str(e)}]

    try:
        objects = export_fn(cluster_url, creds)
        entry = report_builder.objects_to_cluster_entry(cluster_url, objects, old_metrics)
        return entry, []
    except requests.HTTPError as e:
        if e.response is not None and e.response.status_code == 401 and cache_dir and use_browser:
            auth.invalidate_cached_credentials(cache_dir, cluster_url)
            try:
                creds = auth.get_credentials(
                    cluster_url, credentials_map, cache_dir, use_browser=True
                )
                objects = export_fn(cluster_url, creds)
                entry = report_builder.objects_to_cluster_entry(cluster_url, objects, old_metrics)
                return entry, []
            except Exception as retry_e:
                return None, [{"cluster": cluster_url, "phase": "export", "error": str(retry_e)}]
        return None, [{"cluster": cluster_url, "phase": "export", "error": str(e)}]
    except Exception as e:
        return None, [{"cluster": cluster_url, "phase": "export", "error": str(e)}]


def run(
    clusters: list[str],
    metrics_config: list[dict[str, str]],
    output_dir: str,
    credentials_map: dict[str, str] | None,
    cache_dir: str,
    use_browser: bool,
    max_consecutive_failures: int = 5,
) -> int:
    """
    Process each cluster, write report and errors JSON. Returns 0 on success, non-zero if
    we hit max_consecutive_failures or could not write outputs.
    """
    old_metrics = core.get_old_metric_names(metrics_config)

    def get_creds(url: str) -> dict[str, Any]:
        return auth.get_credentials(url, credentials_map, cache_dir, use_browser)

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
            cache_dir,
            use_browser,
            credentials_map,
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
    parser.add_argument(
        "--credentials",
        help="Optional JSON file mapping Kibana URL -> API key to skip browser auth",
    )
    parser.add_argument(
        "--cache-dir",
        default=".cache",
        help="Directory for credential cache (default: .cache)",
    )
    parser.add_argument(
        "--no-browser",
        action="store_true",
        help="Do not open browser for Okta; use only --credentials and cache (fail if missing)",
    )
    args = parser.parse_args()

    try:
        clusters = load_cluster_list(args.clusters)
        metrics_config = load_metrics_config(args.metrics)
    except Exception as e:
        print(f"Error loading config: {e}", file=sys.stderr)
        return 1

    credentials_map = None
    if args.credentials:
        try:
            credentials_map = load_credentials_map(args.credentials)
        except Exception as e:
            print(f"Error loading credentials file: {e}", file=sys.stderr)
            return 1

    return run(
        clusters=clusters,
        metrics_config=metrics_config,
        output_dir=args.output_dir,
        credentials_map=credentials_map,
        cache_dir=args.cache_dir,
        use_browser=not args.no_browser,
    )


if __name__ == "__main__":
    sys.exit(main())
