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
Pure logic for building the report: parse cluster list and credentials, build
cluster entries from saved objects, apply stop-after-N-failures policy.
No I/O; all functions take and return in-memory data for testability.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

import core


def parse_cluster_list(content: str) -> list[str]:
    """
    Parse raw file content into a list of normalized Kibana URLs.
    Skips empty lines and lines starting with #; deduplicates while preserving order.
    """
    lines = content.strip().splitlines()
    urls = []
    seen = set()
    for line in lines:
        if "#" in line:
            line = line.split("#", 1)[0]
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        url = core.normalize_kibana_url(line)
        if url not in seen:
            seen.add(url)
            urls.append(url)
    return urls


def parse_credentials_map(data: Any) -> dict[str, str]:
    """
    Parse a decoded JSON object into a normalized map of Kibana URL -> API key.
    Raises ValueError if data is not a dict. Keys are normalized; non-string or empty values are skipped.
    """
    if not isinstance(data, dict):
        raise ValueError("credentials file must be a JSON object")
    return {
        core.normalize_kibana_url(k): v
        for k, v in data.items()
        if isinstance(v, str) and v
    }


def objects_to_cluster_entry(
    cluster_url: str,
    objects: Iterable[dict[str, Any]],
    old_metrics: list[str],
    metrics_config: list[dict[str, str]],
) -> dict[str, Any]:
    """
    Build a single cluster report entry from an iterable of saved-object dicts.
    Only objects that reference at least one old metric are included.
    Each metric_reference includes new_metric from metrics_config for automation.
    """
    old_to_new = {m["old"]: m["new"] for m in metrics_config}
    objects_with_refs = []
    for obj in objects:
        refs = core.scan_saved_object(obj, old_metrics)
        if refs:
            for ref in refs:
                ref["new_metric"] = old_to_new.get(ref["old_metric"], ref["old_metric"])
            obj_type = obj.get("type", "")
            obj_id = obj.get("id", "")
            entry = {
                "type": obj_type,
                "id": obj_id,
                "title": core.get_title_from_object(obj),
                "metric_references": refs,
            }
            view_url = core.saved_object_view_url(cluster_url, obj_type, obj_id)
            if view_url is not None:
                entry["view_url"] = view_url
            objects_with_refs.append(entry)
    return core.build_cluster_entry(cluster_url, objects_with_refs)


def apply_stop_policy(
    results: list[tuple[dict[str, Any] | None, list[dict[str, Any]]]],
    max_consecutive_failures: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], bool]:
    """
    From a list of (entry, errs) per cluster, compute accumulated cluster_entries,
    all_errors (with consecutive_failure_count set), and whether we stopped early
    due to hitting max_consecutive_failures.
    """
    cluster_entries = []
    all_errors = []
    consecutive_failures = 0
    stopped_early = False

    for entry, errs in results:
        if entry is not None:
            cluster_entries.append(entry)
            consecutive_failures = 0
        if errs:
            for err in errs:
                err_with_count = dict(err)
                err_with_count["consecutive_failure_count"] = consecutive_failures + 1
                all_errors.append(err_with_count)
            consecutive_failures += 1
            if consecutive_failures >= max_consecutive_failures:
                stopped_early = True
                break

    return cluster_entries, all_errors, stopped_early
