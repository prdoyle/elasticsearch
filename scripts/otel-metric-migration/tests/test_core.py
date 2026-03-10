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


# ---- Corner cases / nasty inputs ----


def test_normalize_kibana_url_uppercase_scheme():
    """Uppercase HTTP/HTTPS scheme should still be recognized and normalized."""
    assert core.normalize_kibana_url("HTTP://FOO.COM") == "https://foo.com"
    assert core.normalize_kibana_url("HTTPS://BAR.ELASTIC-CLOUD.COM/") == "https://bar.elastic-cloud.com"


def test_normalize_kibana_url_consistent_lowercasing_with_scheme():
    """With scheme, entire URL is lowercased (host and path)."""
    assert core.normalize_kibana_url("HTTPS://Foo.KB.Region.AWS.Elastic-Cloud.COM") == "https://foo.kb.region.aws.elastic-cloud.com"
    assert core.normalize_kibana_url("HTTP://HOST.COM/App/Home") == "https://host.com/app/home"


def test_normalize_kibana_url_consistent_lowercasing_no_scheme():
    """Without scheme, we still lowercase so output is always consistent (no mixed case)."""
    assert core.normalize_kibana_url("Foo.COM") == "https://foo.com"
    assert core.normalize_kibana_url("Foo.COM/Path/To/Kibana") == "https://foo.com/path/to/kibana"


def test_normalize_kibana_url_same_casing_dedup():
    """Different casings of the same URL normalize to the same string (for dedup/credentials key)."""
    variants = [
        "https://FOO.COM",
        "HTTPS://foo.com",
        "https://foo.com",
        "FOO.COM",
        "foo.com",
    ]
    normalized = [core.normalize_kibana_url(v) for v in variants]
    assert len(set(normalized)) == 1
    assert normalized[0] == "https://foo.com"


def test_normalize_kibana_url_with_path_not_error():
    """URL with path is accepted; path is preserved (lowercased) and trailing slash stripped. Not an error."""
    out = core.normalize_kibana_url("https://cluster.kb.region.aws.elastic-cloud.com/app/home/")
    assert out == "https://cluster.kb.region.aws.elastic-cloud.com/app/home"
    out2 = core.normalize_kibana_url("HTTP://Host.COM/App/Home")
    assert out2 == "https://host.com/app/home"


def test_normalize_kibana_url_path_only_no_host():
    """Edge case: no scheme and path-like string (e.g. /app/home) becomes https:///app/home (lowercased)."""
    out = core.normalize_kibana_url("/app/home")
    assert out == "https:///app/home"


def test_normalize_kibana_url_whitespace_only():
    """Whitespace-only URL becomes https:// (edge case)."""
    out = core.normalize_kibana_url("   \t  ")
    assert out == "https://"


def test_derive_es_url_kb_in_path():
    """If .kb. appears in path or query, replace() mutates it too (global replace)."""
    url = "https://cluster.kb.region.aws.elastic-cloud.com/api/.kb./export"
    out = core.derive_es_url(url)
    assert ".es." in out
    assert out == "https://cluster.es.region.aws.elastic-cloud.com/api/.es./export"


def test_get_title_from_object_attributes_not_dict():
    """attributes as list or string should not crash; return empty title."""
    assert core.get_title_from_object({"attributes": []}) == ""
    assert core.get_title_from_object({"attributes": "x"}) == ""
    assert core.get_title_from_object({"attributes": None}) == ""


def test_scan_saved_object_empty_string_metric_matches_everywhere():
    """Empty string in old_metrics is substring of every string -> many matches."""
    obj = {"a": "hello", "b": "world"}
    refs = core.scan_saved_object(obj, [""])
    assert len(refs) == 1
    assert refs[0]["old_metric"] == ""
    assert len(refs[0]["locations"]) == 2


def test_scan_saved_object_substring_false_positive():
    """Substring match: 'cpu' matches inside 'system.cpu.usage' and 'my_cpu_field'."""
    obj = {"attributes": {"visState": '{"field":"system.cpu.usage","other":"my_cpu_field"}'}}
    refs = core.scan_saved_object(obj, ["cpu"])
    assert len(refs) == 1
    assert refs[0]["old_metric"] == "cpu"
    assert len(refs[0]["locations"]) >= 1


def test_scan_saved_object_single_dot_matches_strings_containing_dot():
    """Metric '.' matches any string containing a literal dot (plain 'in' check, not regex)."""
    obj = {"attributes": {"title": "CPU usage", "visState": "{\"field\":\"system.cpu\"}"}}
    refs = core.scan_saved_object(obj, ["."])
    assert len(refs) == 1
    assert refs[0]["old_metric"] == "."
    assert len(refs[0]["locations"]) >= 1


def test_parse_metrics_config_empty_string_rejected():
    """Empty string 'old' or 'new' is rejected."""
    with pytest.raises(ValueError, match="non-empty"):
        core.parse_metrics_config([{"old": "", "new": "n"}])
    with pytest.raises(ValueError, match="non-empty"):
        core.parse_metrics_config([{"old": "o", "new": ""}])


def test_parse_metrics_config_whitespace_only_accepted():
    """Whitespace-only 'old'/'new' is truthy and currently accepted (no strip)."""
    parsed = core.parse_metrics_config([{"old": "  ", "new": "  "}])
    assert parsed == [{"old": "  ", "new": "  "}]


def test_build_full_report_empty_clusters():
    """Empty clusters list is valid."""
    report = core.build_full_report([], [{"old": "m", "new": "n"}], generated_at="2025-01-01T00:00:00Z")
    assert report["clusters"] == []
    assert report["metrics_config_used"] == [{"old": "m", "new": "n"}]
