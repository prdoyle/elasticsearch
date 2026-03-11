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

"""Unit tests for report_metric_references: process_cluster 401 recovery, _api_key_page_url, _save_credentials_to_file."""

import json
from pathlib import Path
from unittest.mock import MagicMock

import requests

import report_metric_references


def _make_401_response():
    resp = MagicMock()
    resp.status_code = 401
    return resp


def _make_401_error():
    err = requests.HTTPError("401 Unauthorized")
    err.response = _make_401_response()
    return err


def _sample_export_objects():
    return [
        {"type": "dashboard", "id": "d1", "attributes": {"title": "CPU", "visState": "{}"}},
    ]


# ---- _api_key_page_url ----


def test_api_key_page_url_builds_correct_url():
    url = report_metric_references._api_key_page_url("https://Host.com/app")
    assert url == "https://host.com/app/app/management/security/api_keys"
    url = report_metric_references._api_key_page_url("https://foo.kb.region.aws.elastic-cloud.com")
    assert url == "https://foo.kb.region.aws.elastic-cloud.com/app/management/security/api_keys"


# ---- _save_credentials_to_file ----


def test_save_credentials_to_file_creates_file_when_missing(tmp_path):
    cred_path = tmp_path / "creds.json"
    report_metric_references._save_credentials_to_file(
        cred_path, "https://foo.com", "my-api-key"
    )
    data = json.loads(cred_path.read_text())
    assert data == {"https://foo.com": "my-api-key"}


def test_save_credentials_to_file_appends_to_existing(tmp_path):
    cred_path = tmp_path / "creds.json"
    cred_path.write_text(json.dumps({"https://a.com": "key-a"}))
    report_metric_references._save_credentials_to_file(
        cred_path, "https://b.com", "key-b"
    )
    data = json.loads(cred_path.read_text())
    assert data == {"https://a.com": "key-a", "https://b.com": "key-b"}


def test_save_credentials_to_file_updates_existing_url(tmp_path):
    cred_path = tmp_path / "creds.json"
    cred_path.write_text(json.dumps({"https://foo.com": "old-key"}))
    report_metric_references._save_credentials_to_file(
        cred_path, "https://foo.com", "new-key"
    )
    data = json.loads(cred_path.read_text())
    assert data == {"https://foo.com": "new-key"}


# ---- process_cluster: 401 + paste key flow ----


def test_process_cluster_401_user_pastes_key_second_export_succeeds(tmp_path):
    cluster_url = "https://foo.kb.example.com"
    old_metrics = ["system.cpu.usage"]
    api_keys_path = tmp_path / "api-keys.json"
    get_credentials_fn = MagicMock(return_value={"headers": {"Authorization": "ApiKey x"}})
    export_calls = []

    def export_fn(url, creds):
        export_calls.append((url, creds))
        if len(export_calls) == 1:
            raise _make_401_error()
        return _sample_export_objects()

    credentials_map = {}

    entry, errs = report_metric_references.process_cluster(
        cluster_url,
        old_metrics,
        get_credentials_fn,
        export_fn,
        api_keys_path=api_keys_path,
        credentials_map=credentials_map,
        collect_api_key_fn=lambda url: "pasted-key-123",
    )

    assert len(export_calls) == 2
    assert export_calls[1][1]["headers"]["Authorization"] == "ApiKey pasted-key-123"
    assert entry is not None
    assert errs == []
    assert credentials_map.get("https://foo.kb.example.com") == "pasted-key-123"


def test_process_cluster_401_user_skips_empty_key(tmp_path):
    cluster_url = "https://foo.kb.example.com"
    api_keys_path = tmp_path / "api-keys.json"
    get_credentials_fn = MagicMock(return_value={"headers": {}})

    def export_fn(*args, **kwargs):
        raise _make_401_error()

    entry, errs = report_metric_references.process_cluster(
        cluster_url,
        ["m1"],
        get_credentials_fn,
        export_fn,
        api_keys_path=api_keys_path,
        credentials_map={},
        collect_api_key_fn=lambda url: "",
    )

    assert entry is None
    assert len(errs) == 1
    assert errs[0]["cluster"] == cluster_url
    assert errs[0]["phase"] == "export"


def test_process_cluster_401_user_pastes_key_third_export_still_fails(tmp_path):
    cluster_url = "https://foo.kb.example.com"
    api_keys_path = tmp_path / "api-keys.json"
    get_credentials_fn = MagicMock(return_value={"headers": {}})

    def export_fn(*args, **kwargs):
        raise _make_401_error()

    credentials_map = {}
    entry, errs = report_metric_references.process_cluster(
        cluster_url,
        ["m1"],
        get_credentials_fn,
        export_fn,
        api_keys_path=api_keys_path,
        credentials_map=credentials_map,
        collect_api_key_fn=lambda url: "bad-key",
    )

    assert entry is None
    assert len(errs) == 1
    assert "401" in errs[0]["error"]
    assert credentials_map == {}


def test_process_cluster_401_collect_api_key_returns_empty_returns_error(tmp_path):
    """When collect_api_key_fn returns empty string (e.g. user skipped or not interactive), we get an error."""
    cluster_url = "https://foo.kb.example.com"
    api_keys_path = tmp_path / "api-keys.json"
    get_credentials_fn = MagicMock(return_value={"headers": {}})

    def export_fn(*args, **kwargs):
        raise _make_401_error()

    entry, errs = report_metric_references.process_cluster(
        cluster_url,
        ["m1"],
        get_credentials_fn,
        export_fn,
        api_keys_path=api_keys_path,
        credentials_map={},
        collect_api_key_fn=lambda url: "",
    )

    assert entry is None
    assert len(errs) == 1


def test_process_cluster_401_pasted_key_success_writes_api_keys_file(tmp_path):
    cluster_url = "https://foo.kb.example.com"
    api_keys_path = tmp_path / "api-keys.json"
    get_credentials_fn = MagicMock(return_value={"headers": {}})
    export_calls = []

    def export_fn(url, creds):
        export_calls.append(1)
        if len(export_calls) == 1:
            raise _make_401_error()
        return _sample_export_objects()

    credentials_map = {}
    entry, errs = report_metric_references.process_cluster(
        cluster_url,
        ["m1"],
        get_credentials_fn,
        export_fn,
        api_keys_path=api_keys_path,
        credentials_map=credentials_map,
        collect_api_key_fn=lambda url: "saved-key-456",
    )

    assert entry is not None
    assert errs == []
    assert api_keys_path.exists()
    data = json.loads(api_keys_path.read_text())
    assert data == {"https://foo.kb.example.com": "saved-key-456"}


def test_process_cluster_401_pasted_key_success_credentials_map_existing_adds_entry(tmp_path):
    cluster_url = "https://foo.kb.example.com"
    api_keys_path = tmp_path / "api-keys.json"
    get_credentials_fn = MagicMock(return_value={"headers": {}})
    export_calls = []

    def export_fn(url, creds):
        export_calls.append(1)
        if len(export_calls) == 1:
            raise _make_401_error()
        return _sample_export_objects()

    credentials_map = {"https://other.com": "other-key"}
    entry, errs = report_metric_references.process_cluster(
        cluster_url,
        ["m1"],
        get_credentials_fn,
        export_fn,
        api_keys_path=api_keys_path,
        credentials_map=credentials_map,
        collect_api_key_fn=lambda url: "pasted-key",
    )

    assert entry is not None
    assert credentials_map["https://other.com"] == "other-key"
    assert credentials_map["https://foo.kb.example.com"] == "pasted-key"
