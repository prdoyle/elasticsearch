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
Pure logic for scanning saved objects for metric references and building reports.
No I/O; all functions take and return in-memory data for testability.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any


def normalize_kibana_url(url: str) -> str:
    """
    Return a normalized Kibana base URL: strip trailing slash, no fragment,
    scheme forced to https, entire URL lowercased for consistent dedup and comparison.
    Paths are preserved (lowercased); not treated as an error.
    """
    u = url.strip().rstrip("/")
    lower = u.lower()
    if lower.startswith("https://"):
        return lower
    if lower.startswith("http://"):
        return "https://" + lower[7:]
    return "https://" + lower


def derive_es_url(kibana_url: str) -> str:
    """Derive Elasticsearch URL from Kibana URL by replacing .kb. with .es. in host."""
    # Simple host replacement; works for patterns like *.kb.*.aws.elastic-cloud.com
    return kibana_url.replace(".kb.", ".es.")


def _find_metrics_in_string(value: str, old_metrics: list[str]) -> list[tuple[str, str]]:
    """
    If value is a string, check for exact or substring matches of old_metrics.
    Returns list of (matched_metric, snippet) where snippet is truncated value.
    """
    if not isinstance(value, str) or not value:
        return []
    results = []
    snippet = value[:200] + ("..." if len(value) > 200 else "")
    for metric in old_metrics:
        if metric in value:
            results.append((metric, snippet))
    return results


def _walk_and_find(
    obj: Any,
    path: str,
    old_metrics: list[str],
    results: list[tuple[str, str, str]],
) -> None:
    """
    Recursively walk JSON-like structure; append (path, old_metric, snippet) for each match.
    Mutates results in place.
    """
    if isinstance(obj, dict):
        for key, val in obj.items():
            new_path = f"{path}.{key}" if path else key
            _walk_and_find(val, new_path, old_metrics, results)
    elif isinstance(obj, list):
        for i, item in enumerate(obj):
            new_path = f"{path}[{i}]"
            _walk_and_find(item, new_path, old_metrics, results)
    elif isinstance(obj, str):
        for metric, snippet in _find_metrics_in_string(obj, old_metrics):
            results.append((path, metric, snippet))


def scan_saved_object(obj: dict[str, Any], old_metrics: list[str]) -> list[dict[str, Any]]:
    """
    Scan a single saved object for references to any of the old metric names.

    Returns a list of metric_reference dicts: { "old_metric": str, "locations": [ {"path": str, "snippet": str} ] }.
    One entry per old_metric that was found; locations may have multiple items if the metric appears in several places.
    """
    if not old_metrics:
        return []
    results: list[tuple[str, str, str]] = []  # (path, metric, snippet)
    _walk_and_find(obj, "", old_metrics, results)
    # Group by old_metric
    by_metric: dict[str, list[dict[str, str]]] = {}
    for path, metric, snippet in results:
        loc = {"path": path, "snippet": snippet}
        if metric not in by_metric:
            by_metric[metric] = []
        by_metric[metric].append(loc)
    return [{"old_metric": m, "locations": locs} for m, locs in by_metric.items()]


def get_title_from_object(obj: dict[str, Any]) -> str:
    """Extract a human-readable title from a saved object if present."""
    attrs = obj.get("attributes") or {}
    if isinstance(attrs, dict):
        return attrs.get("title") or attrs.get("name") or ""
    return ""


def build_cluster_entry(
    cluster_url: str,
    saved_objects_with_refs: list[dict[str, Any]],
) -> dict[str, Any]:
    """
    Build one cluster's entry for the report: cluster URL and list of saved objects
    that reference old metrics (each with type, id, title, metric_references).
    """
    return {
        "cluster": cluster_url,
        "saved_objects": saved_objects_with_refs,
    }


def build_full_report(
    clusters_data: list[dict[str, Any]],
    metrics_config: list[dict[str, str]],
    generated_at: str | None = None,
) -> dict[str, Any]:
    """
    Build the full report structure: clusters array, metrics_config_used, generated_at.
    clusters_data is a list of cluster entries from build_cluster_entry.
    """
    if generated_at is None:
        generated_at = datetime.now(timezone.utc).isoformat()
    return {
        "clusters": clusters_data,
        "metrics_config_used": metrics_config,
        "generated_at": generated_at,
    }


def parse_metrics_config(config_json: list[Any]) -> list[dict[str, str]]:
    """
    Parse and validate metrics config. Expects list of { "old": str, "new": str }.
    Returns the list; raises ValueError if invalid.
    """
    if not isinstance(config_json, list):
        raise ValueError("metrics config must be a JSON array")
    result = []
    for i, item in enumerate(config_json):
        if not isinstance(item, dict):
            raise ValueError(f"metrics config[{i}] must be an object")
        old_val = item.get("old")
        new_val = item.get("new")
        if not isinstance(old_val, str) or not old_val:
            raise ValueError(f"metrics config[{i}] must have non-empty 'old' string")
        if not isinstance(new_val, str) or not new_val:
            raise ValueError(f"metrics config[{i}] must have non-empty 'new' string")
        result.append({"old": old_val, "new": new_val})
    return result


def get_old_metric_names(metrics_config: list[dict[str, str]]) -> list[str]:
    """Return the list of old metric names from a parsed metrics config."""
    return [m["old"] for m in metrics_config]
