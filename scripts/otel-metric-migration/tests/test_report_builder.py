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

"""Unit tests for report_builder: parsing, objects_to_cluster_entry, apply_stop_policy."""

import pytest

import report_builder


# ---- parse_cluster_list ----


def test_parse_cluster_list_empty():
    assert report_builder.parse_cluster_list("") == []
    assert report_builder.parse_cluster_list("   \n  \n  ") == []


def test_parse_cluster_list_only_comments():
    assert report_builder.parse_cluster_list("# comment\n# another") == []


def test_parse_cluster_list_blank_lines():
    assert report_builder.parse_cluster_list("https://a.com\n\nhttps://b.com\n") == [
        "https://a.com",
        "https://b.com",
    ]


def test_parse_cluster_list_one_url():
    assert report_builder.parse_cluster_list("https://foo.kb.region.aws.elastic-cloud.com") == [
        "https://foo.kb.region.aws.elastic-cloud.com",
    ]


def test_parse_cluster_list_dedup():
    content = "https://a.com\nhttps://a.com\nhttps://b.com\nhttps://a.com"
    assert report_builder.parse_cluster_list(content) == ["https://a.com", "https://b.com"]


def test_parse_cluster_list_trailing_slash_and_comment():
    content = "https://foo.com/  # my cluster"
    assert report_builder.parse_cluster_list(content) == ["https://foo.com"]


def test_parse_cluster_list_order_preserved():
    content = "https://first.com\nhttps://second.com\nhttps://third.com"
    assert report_builder.parse_cluster_list(content) == [
        "https://first.com",
        "https://second.com",
        "https://third.com",
    ]


# ---- parse_credentials_map ----


def test_parse_credentials_map_valid():
    data = {"https://a.com": "key1", "https://b.com/": "key2"}
    assert report_builder.parse_credentials_map(data) == {
        "https://a.com": "key1",
        "https://b.com": "key2",
    }


def test_parse_credentials_map_empty_dict():
    assert report_builder.parse_credentials_map({}) == {}


def test_parse_credentials_map_non_dict_raises():
    with pytest.raises(ValueError, match="credentials file must be a JSON object"):
        report_builder.parse_credentials_map([])
    with pytest.raises(ValueError, match="credentials file must be a JSON object"):
        report_builder.parse_credentials_map("string")


def test_parse_credentials_map_keys_normalized():
    data = {"https://foo.com/": "key1"}
    assert report_builder.parse_credentials_map(data) == {"https://foo.com": "key1"}


def test_parse_credentials_map_skip_non_string_values():
    data = {"https://a.com": "key1", "https://b.com": 123, "https://c.com": ""}
    assert report_builder.parse_credentials_map(data) == {"https://a.com": "key1"}


# ---- objects_to_cluster_entry ----


def test_objects_to_cluster_entry_empty():
    metrics_config = [{"old": "system.cpu.usage", "new": "system.cpu.usage.new"}]
    entry = report_builder.objects_to_cluster_entry(
        "https://a.com", [], ["system.cpu.usage"], metrics_config
    )
    assert entry["cluster"] == "https://a.com"
    assert entry["saved_objects"] == []


def test_objects_to_cluster_entry_one_object_no_refs():
    objects = [
        {"type": "dashboard", "id": "d1", "attributes": {"title": "Empty"}},
    ]
    metrics_config = [{"old": "system.cpu.usage", "new": "system.cpu.usage.new"}]
    entry = report_builder.objects_to_cluster_entry(
        "https://a.com", objects, ["system.cpu.usage"], metrics_config
    )
    assert entry["cluster"] == "https://a.com"
    assert entry["saved_objects"] == []


def test_objects_to_cluster_entry_one_object_with_ref():
    objects = [
        {
            "type": "dashboard",
            "id": "d1",
            "attributes": {
                "title": "CPU",
                "visState": '{"field":"system.cpu.usage"}',
            },
        },
    ]
    metrics_config = [{"old": "system.cpu.usage", "new": "system.cpu.usage.new"}]
    entry = report_builder.objects_to_cluster_entry(
        "https://a.com", objects, ["system.cpu.usage"], metrics_config
    )
    assert entry["cluster"] == "https://a.com"
    assert len(entry["saved_objects"]) == 1
    assert entry["saved_objects"][0]["type"] == "dashboard"
    assert entry["saved_objects"][0]["id"] == "d1"
    assert entry["saved_objects"][0]["title"] == "CPU"
    assert entry["saved_objects"][0]["view_url"] == "https://a.com/api/saved_objects/dashboard/d1"
    assert entry["saved_objects"][0]["metric_references"][0]["old_metric"] == "system.cpu.usage"
    assert entry["saved_objects"][0]["metric_references"][0]["new_metric"] == "system.cpu.usage.new"


def test_objects_to_cluster_entry_two_objects_one_with_refs():
    objects = [
        {"type": "dashboard", "id": "d1", "attributes": {"title": "No refs"}},
        {
            "type": "visualization",
            "id": "v1",
            "attributes": {"visState": '{"metric":"system.memory.usage"}'},
        },
    ]
    metrics_config = [{"old": "system.memory.usage", "new": "system.memory.usage.new"}]
    entry = report_builder.objects_to_cluster_entry(
        "https://a.com", objects, ["system.memory.usage"], metrics_config
    )
    assert len(entry["saved_objects"]) == 1
    assert entry["saved_objects"][0]["type"] == "visualization"
    assert entry["saved_objects"][0]["view_url"] == "https://a.com/api/saved_objects/visualization/v1"
    assert entry["saved_objects"][0]["metric_references"][0]["old_metric"] == "system.memory.usage"
    assert entry["saved_objects"][0]["metric_references"][0]["new_metric"] == "system.memory.usage.new"


def test_objects_to_cluster_entry_object_with_empty_id_omits_view_url():
    objects = [
        {
            "type": "dashboard",
            "id": "",
            "attributes": {"title": "No id", "visState": '{"field":"system.cpu.usage"}'},
        },
    ]
    metrics_config = [{"old": "system.cpu.usage", "new": "system.cpu.usage.new"}]
    entry = report_builder.objects_to_cluster_entry(
        "https://a.com", objects, ["system.cpu.usage"], metrics_config
    )
    assert len(entry["saved_objects"]) == 1
    assert "view_url" not in entry["saved_objects"][0]


# ---- apply_stop_policy ----


def test_apply_stop_policy_no_results():
    entries, errors, stopped = report_builder.apply_stop_policy([], 5)
    assert entries == []
    assert errors == []
    assert stopped is False


def test_apply_stop_policy_all_successes():
    results = [
        ({"cluster": "https://a.com", "saved_objects": []}, []),
        ({"cluster": "https://b.com", "saved_objects": []}, []),
    ]
    entries, errors, stopped = report_builder.apply_stop_policy(results, 5)
    assert len(entries) == 2
    assert entries[0]["cluster"] == "https://a.com"
    assert entries[1]["cluster"] == "https://b.com"
    assert errors == []
    assert stopped is False


def test_apply_stop_policy_all_failures():
    results = [
        (None, [{"cluster": "https://a.com", "phase": "export", "error": "e1"}]),
        (None, [{"cluster": "https://b.com", "phase": "auth", "error": "e2"}]),
    ]
    entries, errors, stopped = report_builder.apply_stop_policy(results, 5)
    assert entries == []
    assert len(errors) == 2
    assert errors[0]["consecutive_failure_count"] == 1
    assert errors[1]["consecutive_failure_count"] == 2
    assert stopped is False


def test_apply_stop_policy_stop_after_five_consecutive():
    results = [
        ({"cluster": "https://a.com", "saved_objects": []}, []),
        (None, [{"cluster": "https://b.com", "error": "e1"}]),
        (None, [{"cluster": "https://c.com", "error": "e2"}]),
        (None, [{"cluster": "https://d.com", "error": "e3"}]),
        (None, [{"cluster": "https://e.com", "error": "e4"}]),
        (None, [{"cluster": "https://f.com", "error": "e5"}]),
        ({"cluster": "https://g.com", "saved_objects": []}, []),
    ]
    entries, errors, stopped = report_builder.apply_stop_policy(results, 5)
    assert len(entries) == 1
    assert entries[0]["cluster"] == "https://a.com"
    assert len(errors) == 5
    assert errors[0]["consecutive_failure_count"] == 1
    assert errors[4]["consecutive_failure_count"] == 5
    assert stopped is True


def test_apply_stop_policy_exactly_five_consecutive_then_success():
    results = [
        (None, [{"error": "1"}]),
        (None, [{"error": "2"}]),
        (None, [{"error": "3"}]),
        (None, [{"error": "4"}]),
        (None, [{"error": "5"}]),
        ({"cluster": "https://ok.com", "saved_objects": []}, []),
    ]
    entries, errors, stopped = report_builder.apply_stop_policy(results, 5)
    # We stop after 5 consecutive failures and do not process the success
    assert len(entries) == 0
    assert len(errors) == 5
    assert stopped is True


# ---- Corner cases ----


def test_parse_cluster_list_unicode_in_url():
    """URLs with Unicode (e.g. IDN) are kept as-is; no crash."""
    content = "https://café.example.com\nhttps://münchen.de"
    urls = report_builder.parse_cluster_list(content)
    assert urls == ["https://café.example.com", "https://münchen.de"]


def test_parse_cluster_list_url_with_path_normalized_and_deduped():
    """URLs with path: host/scheme normalized (lowercased), path case preserved; same URL dedupes to one."""
    content = "HTTPS://Foo.KB.Region.AWS.Elastic-Cloud.COM/App/Home\nhttps://foo.kb.region.aws.elastic-cloud.com/App/Home"
    urls = report_builder.parse_cluster_list(content)
    assert len(urls) == 1
    assert urls[0] == "https://foo.kb.region.aws.elastic-cloud.com/App/Home"


def test_parse_cluster_list_url_with_newline_in_line_stripped():
    """Line with internal newline: we split by lines so one URL per line."""
    content = "https://a.com\nhttps://b.com\n"
    urls = report_builder.parse_cluster_list(content)
    assert "https://a.com" in urls and "https://b.com" in urls


def test_apply_stop_policy_max_consecutive_zero():
    """max_consecutive_failures=0: first failure triggers stop (or no failures allowed)."""
    results = [
        (None, [{"cluster": "https://a.com", "error": "e1"}]),
    ]
    entries, errors, stopped = report_builder.apply_stop_policy(results, 0)
    # Behavior: 0 may mean "don't stop" or "stop immediately". Document actual behavior.
    assert len(entries) == 0
    assert len(errors) == 1
    assert stopped is True
