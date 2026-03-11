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

"""Unit tests for export_saved_objects: _object_refs_from_cluster_entry, run with batching."""

import json
from pathlib import Path
from unittest.mock import patch

import pytest

import export_saved_objects


def _object_refs_from_cluster_entry(entry):
    """Re-export for testing."""
    return export_saved_objects._object_refs_from_cluster_entry(entry)


def test_object_refs_from_cluster_entry():
    """Extract type and id from saved_objects in report entry."""
    entry = {
        "cluster": "https://a.kb.example.com",
        "saved_objects": [
            {"type": "dashboard", "id": "id1", "title": "D1"},
            {"type": "visualization", "id": "id2"},
        ],
    }
    assert _object_refs_from_cluster_entry(entry) == [
        {"type": "dashboard", "id": "id1"},
        {"type": "visualization", "id": "id2"},
    ]


def test_object_refs_from_cluster_entry_skips_missing_type_or_id():
    """Entries without type or id are skipped."""
    entry = {
        "cluster": "https://a.kb.example.com",
        "saved_objects": [
            {"type": "dashboard", "id": "id1"},
            {"id": "id2"},
            {"type": "viz"},
        ],
    }
    assert _object_refs_from_cluster_entry(entry) == [
        {"type": "dashboard", "id": "id1"},
    ]


def test_run_report_missing(tmp_path):
    """run() returns 1 when report file is missing."""
    work_dir = tmp_path / "work"
    work_dir.mkdir()
    code = export_saved_objects.run(
        work_dir=work_dir,
        credentials_map={},
        api_keys_path=tmp_path / "api-keys.json",
        export_batch_size=10,
    )
    assert code == 1


def test_run_report_invalid_json(tmp_path):
    """run() returns 1 when report is not valid JSON."""
    work_dir = tmp_path / "work"
    (work_dir / "report").mkdir(parents=True)
    (work_dir / "report" / "metric_references_report.json").write_text("not json")
    code = export_saved_objects.run(
        work_dir=work_dir,
        credentials_map={},
        api_keys_path=tmp_path / "api-keys.json",
        export_batch_size=10,
    )
    assert code == 1


def test_run_batching_multiple_calls_per_cluster(tmp_path):
    """With small batch size, run() triggers multiple export API calls per cluster and concatenates NDJSON."""
    work_dir = tmp_path / "work"
    (work_dir / "report").mkdir(parents=True)
    report = {
        "clusters": [
            {
                "cluster": "https://cluster.kb.example.com",
                "saved_objects": [
                    {"type": "dashboard", "id": f"id{i}", "title": f"Obj{i}"}
                    for i in range(5)
                ],
            }
        ],
    }
    (work_dir / "report" / "metric_references_report.json").write_text(
        json.dumps(report)
    )
    export_calls = []

    def mock_export(base_url, credentials, timeout=120, objects=None):
        export_calls.append((base_url, objects))
        for obj in objects or []:
            yield {"type": obj["type"], "id": obj["id"], "attributes": {}}

    with patch("export_saved_objects.kibana_client.export_saved_objects", side_effect=mock_export):
        with patch("export_saved_objects.auth.get_credentials") as mock_creds:
            mock_creds.return_value = {"headers": {"kbn-xsrf": "true"}}
            code = export_saved_objects.run(
                work_dir=work_dir,
                credentials_map={"https://cluster.kb.example.com": "fake-key"},
                api_keys_path=tmp_path / "api-keys.json",
                export_batch_size=2,
            )
    assert code == 0
    assert len(export_calls) == 3
    assert export_calls[0][1] == [
        {"type": "dashboard", "id": "id0"},
        {"type": "dashboard", "id": "id1"},
    ]
    assert export_calls[1][1] == [
        {"type": "dashboard", "id": "id2"},
        {"type": "dashboard", "id": "id3"},
    ]
    assert export_calls[2][1] == [{"type": "dashboard", "id": "id4"}]
    export_dir = work_dir / "export"
    assert export_dir.exists()
    ndjson_files = list(export_dir.glob("*.ndjson"))
    assert len(ndjson_files) == 1
    lines = ndjson_files[0].read_text().strip().split("\n")
    assert len(lines) == 5
