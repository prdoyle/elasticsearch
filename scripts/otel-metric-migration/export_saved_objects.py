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
Export saved objects listed in a metric-references report to NDJSON files per cluster.
Reads report from latest/<env>/report/; writes to latest/<env>/export/. Uses Kibana
_export API with objects parameter; chunks by export_batch_size.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Callable

import requests

import auth
import core
import kibana_client
import report_metric_references


def _object_refs_from_cluster_entry(entry: dict[str, Any]) -> list[dict[str, str]]:
    """Extract [{"type": str, "id": str}, ...] from a report cluster entry."""
    refs = []
    for obj in entry.get("saved_objects") or []:
        if isinstance(obj, dict) and "type" in obj and "id" in obj:
            refs.append({"type": str(obj["type"]), "id": str(obj["id"])})
    return refs


def _chunked(refs: list[dict[str, str]], size: int):
    """Yield chunks of refs of at most size."""
    for i in range(0, len(refs), size):
        yield refs[i : i + size]


def run(
    work_dir: Path,
    credentials_map: dict[str, str],
    api_keys_path: str | Path,
    export_batch_size: int = 10_000,
    collect_api_key_fn: Callable[[str], str] | None = None,
) -> int:
    """
    Load report from work_dir/report/metric_references_report.json, export saved
    objects per cluster (chunked by export_batch_size) to work_dir/export/<slug>.ndjson.
    Returns 0 on success; 1 if report missing/invalid or any cluster failed (errors in
    work_dir/export/export_errors.json).
    """
    if collect_api_key_fn is None:
        collect_api_key_fn = report_metric_references._collect_api_key_via_browser

    report_path = work_dir / "report" / "metric_references_report.json"
    if not report_path.exists():
        print(f"Report not found: {report_path}", file=sys.stderr)
        return 1
    try:
        report = json.loads(report_path.read_text())
    except (json.JSONDecodeError, OSError) as e:
        print(f"Error loading report: {e}", file=sys.stderr)
        return 1

    clusters_data = report.get("clusters")
    if not isinstance(clusters_data, list):
        print("Report has no 'clusters' array.", file=sys.stderr)
        return 1

    export_dir = work_dir / "export"
    export_dir.mkdir(parents=True, exist_ok=True)
    errors: list[dict[str, Any]] = []

    def get_creds(url: str) -> dict[str, Any]:
        return auth.get_credentials(url, credentials_map)

    for entry in clusters_data:
        if not isinstance(entry, dict):
            continue
        cluster_url = entry.get("cluster")
        if not isinstance(cluster_url, str):
            continue
        url = core.normalize_kibana_url(cluster_url)
        refs = _object_refs_from_cluster_entry(entry)
        if not refs:
            continue

        slug = auth.cluster_slug(cluster_url)
        out_path = export_dir / f"{slug}.ndjson"
        lines: list[str] = []

        try:
            creds = get_creds(cluster_url)
        except RuntimeError as e:
            api_key_page_url = report_metric_references._api_key_page_url(cluster_url)
            api_key = collect_api_key_fn(api_key_page_url)
            if not api_key:
                errors.append({"cluster": cluster_url, "phase": "auth", "error": str(e)})
                continue
            credentials_map[url] = api_key
            report_metric_references._save_credentials_to_file(
                api_keys_path, cluster_url, api_key
            )
            try:
                creds = get_creds(cluster_url)
            except Exception as retry_e:
                errors.append({
                    "cluster": cluster_url,
                    "phase": "auth",
                    "error": str(retry_e),
                })
                continue

        for chunk in _chunked(refs, export_batch_size):
            try:
                for obj in kibana_client.export_saved_objects(
                    cluster_url, creds, objects=chunk
                ):
                    lines.append(json.dumps(obj))
            except requests.HTTPError as e:
                if e.response is not None and e.response.status_code == 401:
                    api_key_page_url = report_metric_references._api_key_page_url(
                        cluster_url
                    )
                    api_key = collect_api_key_fn(api_key_page_url)
                    if not api_key:
                        errors.append({
                            "cluster": cluster_url,
                            "phase": "export",
                            "error": str(e),
                        })
                        break
                    credentials_map[url] = api_key
                    report_metric_references._save_credentials_to_file(
                        api_keys_path, cluster_url, api_key
                    )
                    try:
                        creds = get_creds(cluster_url)
                    except Exception:
                        errors.append({
                            "cluster": cluster_url,
                            "phase": "auth",
                            "error": "Failed after saving new API key",
                        })
                        break
                    try:
                        for obj in kibana_client.export_saved_objects(
                            cluster_url, creds, objects=chunk
                        ):
                            lines.append(json.dumps(obj))
                    except Exception as retry_e:
                        errors.append({
                            "cluster": cluster_url,
                            "phase": "export",
                            "error": str(retry_e),
                        })
                        break
                else:
                    errors.append({
                        "cluster": cluster_url,
                        "phase": "export",
                        "error": str(e),
                    })
                    break
            except Exception as e:
                errors.append({
                    "cluster": cluster_url,
                    "phase": "export",
                    "error": str(e),
                })
                break

        if lines:
            out_path.write_text("\n".join(lines) + "\n")

    if errors:
        errors_path = export_dir / "export_errors.json"
        errors_path.write_text(
            json.dumps({"errors": errors, "error_count": len(errors)}, indent=2)
        )
        return 1
    return 0
