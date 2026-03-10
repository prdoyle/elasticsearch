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

"""Unit tests for core scanning and report-building logic."""

import pytest

import core


def test_normalize_kibana_url():
    assert core.normalize_kibana_url("https://foo.kb.region.aws.elastic-cloud.com") == "https://foo.kb.region.aws.elastic-cloud.com"
    assert core.normalize_kibana_url("https://foo.kb.region.aws.elastic-cloud.com/") == "https://foo.kb.region.aws.elastic-cloud.com"
    assert core.normalize_kibana_url("  https://foo.com/  ") == "https://foo.com"
    assert core.normalize_kibana_url("foo.com") == "https://foo.com"


def test_derive_es_url():
    assert core.derive_es_url("https://platform-metrics.kb.af-south-1.aws.elastic-cloud.com") == "https://platform-metrics.es.af-south-1.aws.elastic-cloud.com"


def test_scan_saved_object_empty_metrics():
    obj = {"type": "dashboard", "id": "x", "attributes": {"title": "CPU", "visState": '{"aggs":[{"field":"system.cpu.usage"}]}'}}
    assert core.scan_saved_object(obj, []) == []


def test_scan_saved_object_finds_metric():
    obj = {
        "type": "dashboard",
        "id": "dash-1",
        "attributes": {
            "title": "CPU overview",
            "visState": '{"aggs":[{"params":{"field":"system.cpu.usage"}}]}',
        },
    }
    refs = core.scan_saved_object(obj, ["system.cpu.usage"])
    assert len(refs) == 1
    assert refs[0]["old_metric"] == "system.cpu.usage"
    assert len(refs[0]["locations"]) >= 1
    assert "path" in refs[0]["locations"][0]
    assert "snippet" in refs[0]["locations"][0]
    assert "system.cpu.usage" in refs[0]["locations"][0]["snippet"]


def test_scan_saved_object_multiple_metrics():
    obj = {
        "type": "visualization",
        "id": "vis-1",
        "attributes": {
            "visState": '{"metrics":["system.cpu.usage","system.memory.usage"]}',
        },
    }
    refs = core.scan_saved_object(obj, ["system.cpu.usage", "system.memory.usage"])
    assert len(refs) == 2
    metrics_found = {r["old_metric"] for r in refs}
    assert metrics_found == {"system.cpu.usage", "system.memory.usage"}


def test_scan_saved_object_no_match():
    obj = {"type": "dashboard", "id": "x", "attributes": {"title": "Other"}}
    assert core.scan_saved_object(obj, ["system.cpu.usage"]) == []


def test_get_title_from_object():
    obj = {"attributes": {"title": "My Dashboard"}}
    assert core.get_title_from_object(obj) == "My Dashboard"
    obj2 = {"attributes": {"name": "My Viz"}}
    assert core.get_title_from_object(obj2) == "My Viz"
    assert core.get_title_from_object({}) == ""


def test_build_cluster_entry():
    saved_objects = [
        {"type": "dashboard", "id": "d1", "title": "CPU", "metric_references": [{"old_metric": "system.cpu.usage", "locations": []}]},
    ]
    entry = core.build_cluster_entry("https://foo.kb.example.com", saved_objects)
    assert entry["cluster"] == "https://foo.kb.example.com"
    assert len(entry["saved_objects"]) == 1
    assert entry["saved_objects"][0]["type"] == "dashboard"
    assert entry["saved_objects"][0]["metric_references"][0]["old_metric"] == "system.cpu.usage"


def test_build_full_report():
    clusters_data = [
        core.build_cluster_entry("https://a.example.com", []),
        core.build_cluster_entry("https://b.example.com", [{"type": "dashboard", "id": "1", "title": "X", "metric_references": []}]),
    ]
    metrics_config = [{"old": "m1", "new": "m1.new"}]
    report = core.build_full_report(clusters_data, metrics_config, generated_at="2025-01-01T00:00:00Z")
    assert report["clusters"] == clusters_data
    assert report["metrics_config_used"] == metrics_config
    assert report["generated_at"] == "2025-01-01T00:00:00Z"


def test_parse_metrics_config():
    config = [{"old": "a", "new": "b"}, {"old": "c", "new": "d"}]
    parsed = core.parse_metrics_config(config)
    assert parsed == [{"old": "a", "new": "b"}, {"old": "c", "new": "d"}]


def test_parse_metrics_config_invalid_not_list():
    with pytest.raises(ValueError, match="must be a JSON array"):
        core.parse_metrics_config({})


def test_parse_metrics_config_invalid_missing_old():
    with pytest.raises(ValueError, match="non-empty 'old'"):
        core.parse_metrics_config([{"new": "b"}])


def test_parse_metrics_config_invalid_missing_new():
    with pytest.raises(ValueError, match="non-empty 'new'"):
        core.parse_metrics_config([{"old": "a"}])


def test_get_old_metric_names():
    config = [{"old": "m1", "new": "n1"}, {"old": "m2", "new": "n2"}]
    assert core.get_old_metric_names(config) == ["m1", "m2"]
