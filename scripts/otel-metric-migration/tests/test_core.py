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


def test_derive_es_url():
    assert core.derive_es_url("https://platform-metrics.kb.af-south-1.aws.elastic-cloud.com") == "https://platform-metrics.es.af-south-1.aws.elastic-cloud.com"


def test_saved_object_view_url_dashboard():
    url = core.saved_object_view_url("https://foo.kb.example.com", "dashboard", "dash-123")
    assert url == "https://foo.kb.example.com/api/saved_objects/dashboard/dash-123"


def test_saved_object_view_url_visualization():
    url = core.saved_object_view_url("https://host.com", "visualization", "vis-456")
    assert url == "https://host.com/api/saved_objects/visualization/vis-456"


def test_saved_object_view_url_lens():
    url = core.saved_object_view_url("https://host.com/", "lens", "len-789")
    assert url == "https://host.com/api/saved_objects/lens/len-789"


def test_saved_object_view_url_unknown_type_uses_api():
    url = core.saved_object_view_url("https://host.com", "search", "s1")
    assert url == "https://host.com/api/saved_objects/search/s1"


def test_saved_object_view_url_empty_id_returns_none():
    url = core.saved_object_view_url("https://host.com", "dashboard", "")
    assert url is None


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


def test_scan_saved_object_snippet_contains_metric_when_match_after_200_chars():
    """Snippet is a window around the first occurrence; metric after 200 chars still appears in snippet."""
    long_prefix = "x" * 250
    value = long_prefix + '{"field":"system.cpu.usage"}'
    obj = {"type": "dashboard", "id": "d1", "attributes": {"visState": value}}
    refs = core.scan_saved_object(obj, ["system.cpu.usage"])
    assert len(refs) == 1
    snippet = refs[0]["locations"][0]["snippet"]
    assert "system.cpu.usage" in snippet
    assert snippet.startswith("...")


def test_scan_saved_object_snippet_no_ellipses_when_match_fully_in_window():
    """When the match fits entirely in the snippet window, no ellipses are added."""
    value = '{"field":"system.cpu.usage"}'
    obj = {"type": "dashboard", "id": "d1", "attributes": {"visState": value}}
    refs = core.scan_saved_object(obj, ["system.cpu.usage"])
    assert len(refs) == 1
    snippet = refs[0]["locations"][0]["snippet"]
    assert "system.cpu.usage" in snippet
    assert not snippet.startswith("...")
    assert not snippet.endswith("...")


def test_scan_saved_object_snippet_ellipses_when_truncated_at_start():
    """When text is elided before the match, snippet has leading ellipsis."""
    long_prefix = "a" * 100
    value = long_prefix + '{"metric":"system.cpu.usage"}'
    obj = {"type": "dashboard", "id": "d1", "attributes": {"visState": value}}
    refs = core.scan_saved_object(obj, ["system.cpu.usage"])
    assert len(refs) == 1
    snippet = refs[0]["locations"][0]["snippet"]
    assert "system.cpu.usage" in snippet
    assert snippet.startswith("...")


def test_scan_saved_object_snippet_ellipses_when_truncated_at_end():
    """When text is elided after the match, snippet has trailing ellipsis."""
    value = '{"metric":"system.cpu.usage"}' + "z" * 100
    obj = {"type": "dashboard", "id": "d1", "attributes": {"visState": value}}
    refs = core.scan_saved_object(obj, ["system.cpu.usage"])
    assert len(refs) == 1
    snippet = refs[0]["locations"][0]["snippet"]
    assert "system.cpu.usage" in snippet
    assert snippet.endswith("...")


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
    metrics_config = [{"old": "m1", "new": {"name": "m1.new", "dimensions": {}}}]
    report = core.build_full_report(clusters_data, metrics_config, generated_at="2025-01-01T00:00:00Z")
    assert report["clusters"] == clusters_data
    assert report["metrics_config_used"] == metrics_config
    assert report["generated_at"] == "2025-01-01T00:00:00Z"


def test_parse_metrics_config():
    config = [
        {"old": "a", "new": {"name": "b", "dimensions": {}}},
        {"old": "c", "new": {"name": "d", "dimensions": {}}},
    ]
    parsed = core.parse_metrics_config(config)
    assert parsed == [
        {"old": "a", "new": {"name": "b", "dimensions": {}}},
        {"old": "c", "new": {"name": "d", "dimensions": {}}},
    ]


def test_parse_metrics_config_invalid_not_list():
    with pytest.raises(ValueError, match="must be a JSON array"):
        core.parse_metrics_config({})


def test_parse_metrics_config_invalid_missing_old():
    with pytest.raises(ValueError, match="non-empty 'old'"):
        core.parse_metrics_config([{"new": "b"}])


def test_parse_metrics_config_invalid_missing_new():
    with pytest.raises(ValueError, match="'new' as an object"):
        core.parse_metrics_config([{"old": "a"}])


def test_parse_metrics_config_new_not_object_raises():
    with pytest.raises(ValueError, match="'new' as an object"):
        core.parse_metrics_config([{"old": "a", "new": "b"}])
    with pytest.raises(ValueError, match="'new' as an object"):
        core.parse_metrics_config([{"old": "a", "new": 123}])


def test_parse_metrics_config_new_object_missing_name_raises():
    with pytest.raises(ValueError, match="non-empty 'name'"):
        core.parse_metrics_config([{"old": "a", "new": {"dimensions": {}}}])


def test_parse_metrics_config_new_object_empty_name_raises():
    with pytest.raises(ValueError, match="non-empty 'name'"):
        core.parse_metrics_config([{"old": "a", "new": {"name": "", "dimensions": {}}}])


def test_parse_metrics_config_with_dimensions():
    config = [{"old": "heap.used", "new": {"name": "jvm.memory.used", "dimensions": {"jvm.memory.type": "heap"}}}]
    parsed = core.parse_metrics_config(config)
    assert parsed[0]["new"] == {"name": "jvm.memory.used", "dimensions": {"jvm.memory.type": "heap"}}


def test_get_old_metric_names():
    config = [
        {"old": "m1", "new": {"name": "n1", "dimensions": {}}},
        {"old": "m2", "new": {"name": "n2", "dimensions": {}}},
    ]
    assert core.get_old_metric_names(config) == ["m1", "m2"]


# ---- Corner cases / nasty inputs ----


def test_normalize_kibana_url_uppercase_scheme():
    """Uppercase HTTP/HTTPS scheme should still be recognized and normalized."""
    assert core.normalize_kibana_url("HTTP://FOO.COM") == "https://foo.com"
    assert core.normalize_kibana_url("HTTPS://BAR.ELASTIC-CLOUD.COM/") == "https://bar.elastic-cloud.com"


def test_normalize_kibana_url_consistent_lowercasing_with_scheme():
    """With scheme, host is lowercased; path case is preserved (RFC 3986 path can be case-sensitive)."""
    assert core.normalize_kibana_url("HTTPS://Foo.KB.Region.AWS.Elastic-Cloud.COM") == "https://foo.kb.region.aws.elastic-cloud.com"
    assert core.normalize_kibana_url("HTTP://HOST.COM/App/Home") == "https://host.com/App/Home"


def test_normalize_kibana_url_no_scheme_raises():
    """Scheme is mandatory; URL without scheme raises ValueError."""
    with pytest.raises(ValueError, match="scheme"):
        core.normalize_kibana_url("Foo.COM")
    with pytest.raises(ValueError, match="scheme"):
        core.normalize_kibana_url("Foo.COM/Path/To/Kibana")
    with pytest.raises(ValueError, match="scheme"):
        core.normalize_kibana_url("foo.com")


def test_normalize_kibana_url_same_casing_dedup():
    """Different casings of the same URL (with scheme) normalize to the same string."""
    variants = [
        "https://FOO.COM",
        "HTTPS://foo.com",
        "https://foo.com",
        "HTTP://foo.com",
    ]
    normalized = [core.normalize_kibana_url(v) for v in variants]
    assert len(set(normalized)) == 1
    assert normalized[0] == "https://foo.com"


def test_normalize_kibana_url_with_path_not_error():
    """URL with path is accepted; path case preserved, trailing slash stripped. Not an error."""
    out = core.normalize_kibana_url("https://cluster.kb.region.aws.elastic-cloud.com/app/home/")
    assert out == "https://cluster.kb.region.aws.elastic-cloud.com/app/home"
    out2 = core.normalize_kibana_url("HTTP://Host.COM/App/Home")
    assert out2 == "https://host.com/App/Home"


def test_normalize_kibana_url_path_case_preserved():
    """Path and query case are never lowercased; only scheme and host are (RFC 3986)."""
    assert core.normalize_kibana_url("HTTPS://Host.com/App/Home") == "https://host.com/App/Home"
    assert core.normalize_kibana_url("https://host.com/API/V1/Export") == "https://host.com/API/V1/Export"


def test_normalize_kibana_url_query_preserved():
    """Query string is preserved (case and encoding); host lowercased only."""
    assert core.normalize_kibana_url("https://Host.com/app?foo=bar") == "https://host.com/app?foo=bar"
    assert core.normalize_kibana_url("https://Host.com?Key=Val&other=value") == "https://host.com?Key=Val&other=value"


def test_normalize_kibana_url_query_corner_cases():
    """Query corner cases: encoding, empty params, reserved chars, fragment stripped."""
    # URL-encoded values preserved as-is (no decode/reencode)
    out = core.normalize_kibana_url("https://foo.com/path?x=%2F%2F&y=hello%20world")
    assert out == "https://foo.com/path?x=%2F%2F&y=hello%20world"
    # Double-encoding (mistake): preserved literally
    out = core.normalize_kibana_url("https://foo.com?a=%252F")
    assert out == "https://foo.com?a=%252F"
    # Empty param value
    out = core.normalize_kibana_url("https://foo.com?a=&b=1")
    assert out == "https://foo.com?a=&b=1"
    # Value containing unencoded = (ambiguous but preserved)
    out = core.normalize_kibana_url("https://foo.com?key=val=with=equals")
    assert "key=val=with=equals" in out or out == "https://foo.com?key=val=with=equals"
    # Fragment is stripped (not included in normalized URL)
    out = core.normalize_kibana_url("https://foo.com/path?q=1#section")
    assert out == "https://foo.com/path?q=1"
    assert "#" not in out


def test_normalize_kibana_url_no_scheme_path_only_raises():
    """Path-only (no scheme, no host) raises ValueError."""
    with pytest.raises(ValueError, match="scheme"):
        core.normalize_kibana_url("/App/Home")
    with pytest.raises(ValueError, match="scheme"):
        core.normalize_kibana_url("/app/home")


def test_normalize_kibana_url_whitespace_only_raises():
    """Whitespace-only URL has no scheme and raises ValueError."""
    with pytest.raises(ValueError, match="scheme"):
        core.normalize_kibana_url("   \t  ")


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
    """Empty string 'old' or 'new.name' is rejected."""
    with pytest.raises(ValueError, match="non-empty 'old'"):
        core.parse_metrics_config([{"old": "", "new": {"name": "n", "dimensions": {}}}])
    with pytest.raises(ValueError, match="non-empty 'name'"):
        core.parse_metrics_config([{"old": "o", "new": {"name": "", "dimensions": {}}}])


def test_parse_metrics_config_whitespace_only_accepted():
    """Whitespace-only 'old'/'name' is truthy and currently accepted (no strip)."""
    parsed = core.parse_metrics_config([{"old": "  ", "new": {"name": "  ", "dimensions": {}}}])
    assert parsed == [{"old": "  ", "new": {"name": "  ", "dimensions": {}}}]


def test_build_full_report_empty_clusters():
    """Empty clusters list is valid."""
    metrics_config = [{"old": "m", "new": {"name": "n", "dimensions": {}}}]
    report = core.build_full_report([], metrics_config, generated_at="2025-01-01T00:00:00Z")
    assert report["clusters"] == []
    assert report["metrics_config_used"] == metrics_config
