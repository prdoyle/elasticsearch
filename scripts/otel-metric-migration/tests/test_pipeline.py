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

"""Unit tests for config parsing: parse_config, get_env_config, validate_env_config, validate_step_name, step_output_dir."""

from pathlib import Path

import pytest

import pipeline


# ---- validate_step_name ----


def test_validate_step_name_valid():
    """Valid step names do not raise (no periods allowed)."""
    for name in ("qa_report", "qa-report", "step1"):
        pipeline.validate_step_name(name)


def test_validate_step_name_empty():
    """Empty string raises ValueError with clear message."""
    with pytest.raises(ValueError, match="non-empty and contain only"):
        pipeline.validate_step_name("")


def test_validate_step_name_invalid_characters():
    """Step names with space, slash, period, or other invalid chars raise."""
    with pytest.raises(ValueError, match="non-empty and contain only"):
        pipeline.validate_step_name("step name")
    with pytest.raises(ValueError, match="non-empty and contain only"):
        pipeline.validate_step_name("path/to/step")
    with pytest.raises(ValueError, match="non-empty and contain only"):
        pipeline.validate_step_name("step\tname")
    with pytest.raises(ValueError, match="non-empty and contain only"):
        pipeline.validate_step_name("my.step.name")
    with pytest.raises(ValueError, match="non-empty and contain only"):
        pipeline.validate_step_name("..")


# ---- step_output_dir ----


def test_step_output_dir_default_base():
    """Step output dir is script_dir / base / run_timestamp / step_name."""
    assert pipeline.step_output_dir(
        Path("/script"), "out", "20250101T120000Z", "qa_report"
    ) == Path("/script/out/20250101T120000Z/qa_report")


def test_step_output_dir_custom_base():
    """Step output dir with custom base (e.g. from config output_dir)."""
    assert pipeline.step_output_dir(
        Path("/script"), "reports", "20250101T120000Z", "prod_report"
    ) == Path("/script/reports/20250101T120000Z/prod_report")


# ---- parse_config ----


def test_parse_config_valid():
    """Valid config with environments and metrics returns (metrics_list, environments)."""
    data = {
        "environments": {"qa": {"clusters": "qa-clusters.txt"}},
        "metrics": [
            {"old": "m1", "new": {"name": "m2", "dimensions": {}}},
        ],
    }
    metrics_list, environments = pipeline.parse_config(data)
    assert len(metrics_list) == 1
    assert metrics_list[0]["old"] == "m1"
    assert metrics_list[0]["new"] == {"name": "m2", "dimensions": {}}
    assert environments == {"qa": {"clusters": "qa-clusters.txt"}}


def test_parse_config_missing_environments():
    """Config without 'environments' raises."""
    with pytest.raises(ValueError, match="'environments'"):
        pipeline.parse_config({"metrics": []})


def test_parse_config_missing_metrics():
    """Config without 'metrics' raises."""
    with pytest.raises(ValueError, match="'metrics'"):
        pipeline.parse_config({"environments": {"qa": {"clusters": "c.txt"}}})


def test_parse_config_empty_environments():
    """Config with empty environments raises."""
    with pytest.raises(ValueError, match="non-empty"):
        pipeline.parse_config(
            {"environments": {}, "metrics": [{"old": "a", "new": {"name": "b", "dimensions": {}}}]}
        )


def test_parse_config_invalid_metrics_shape():
    """Invalid metrics (e.g. missing 'old') raises."""
    with pytest.raises(ValueError, match="metrics"):
        pipeline.parse_config(
            {
                "environments": {"qa": {"clusters": "c.txt"}},
                "metrics": [{"new": {"name": "b", "dimensions": {}}}],
            }
        )


def test_parse_config_root_not_dict():
    """Config must be a dict."""
    with pytest.raises(ValueError, match="dict"):
        pipeline.parse_config([])


# ---- get_env_config ----


def test_get_env_config_valid():
    """Valid env returns its config."""
    environments = {"qa": {"clusters": "qa-clusters.txt"}}
    assert pipeline.get_env_config(environments, "qa") == {
        "clusters": "qa-clusters.txt"
    }


def test_get_env_config_unknown_env():
    """Unknown env raises."""
    environments = {"qa": {"clusters": "c.txt"}}
    with pytest.raises(ValueError, match="unknown environment"):
        pipeline.get_env_config(environments, "prod")


def test_get_env_config_invalid_env_name():
    """Invalid env name (e.g. contains space) raises."""
    environments = {"qa": {"clusters": "c.txt"}}
    with pytest.raises(ValueError, match="non-empty and contain only"):
        pipeline.get_env_config(environments, "bad env")


# ---- validate_env_config ----


def test_validate_env_config_valid():
    """Valid env config with clusters does not raise."""
    pipeline.validate_env_config({"clusters": "clusters.txt"})


def test_validate_env_config_missing_clusters():
    """Missing 'clusters' raises."""
    with pytest.raises(ValueError, match="'clusters'"):
        pipeline.validate_env_config({})


def test_validate_env_config_empty_clusters():
    """Empty 'clusters' raises."""
    with pytest.raises(ValueError, match="'clusters'"):
        pipeline.validate_env_config({"clusters": ""})
