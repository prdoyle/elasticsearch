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

"""Unit tests for pipeline parsing: parse_pipeline, validate_report_config, resolve_preset_filename."""

import pytest

import pipeline


# ---- resolve_preset_filename ----


def test_resolve_preset_filename_adds_yaml_when_missing():
    """When preset_file has no .yaml suffix, .yaml is appended."""
    assert pipeline.resolve_preset_filename("qa-report") == "qa-report.yaml"


def test_resolve_preset_filename_unchanged_when_has_yaml():
    """When preset_file already ends with .yaml, it is returned unchanged."""
    assert pipeline.resolve_preset_filename("qa-report.yaml") == "qa-report.yaml"


def test_resolve_preset_filename_case_sensitive_yaml():
    """Only literal .yaml suffix is recognized; .YAML gets .yaml appended."""
    assert pipeline.resolve_preset_filename("some-preset.YAML") == "some-preset.YAML.yaml"


def test_resolve_preset_filename_rejects_path_separator():
    """Preset filename containing / raises ValueError."""
    with pytest.raises(ValueError, match="path separators or '..'"):
        pipeline.resolve_preset_filename("path/to/preset.yaml")


def test_resolve_preset_filename_rejects_dotdot():
    """Preset filename containing .. raises ValueError."""
    with pytest.raises(ValueError, match="path separators or '..'"):
        pipeline.resolve_preset_filename("../preset.yaml")


def test_resolve_preset_filename_rejects_path_separator_and_dotdot():
    """Preset filename containing both / and .. raises ValueError."""
    with pytest.raises(ValueError, match="path separators or '..'"):
        pipeline.resolve_preset_filename("foo/../bar.yaml")


# ---- parse_pipeline: valid cases ----


def test_parse_pipeline_single_step():
    """Single report step translates to one (verb, config) tuple."""
    data = {
        "steps": [
            {
                "report": {
                    "clusters": "clusters.txt",
                    "metrics": "metrics_config.json",
                }
            }
        ]
    }
    result = pipeline.parse_pipeline(data)
    assert result == [
        (
            "report",
            {"clusters": "clusters.txt", "metrics": "metrics_config.json"},
        )
    ]


def test_parse_pipeline_two_steps():
    """Two steps yield two tuples in order."""
    data = {
        "steps": [
            {"report": {"clusters": "qa-clusters.txt", "metrics": "metrics_config.json"}},
            {"report": {"clusters": "prod-clusters.txt", "metrics": "metrics_config.json"}},
        ]
    }
    result = pipeline.parse_pipeline(data)
    assert len(result) == 2
    assert result[0] == (
        "report",
        {"clusters": "qa-clusters.txt", "metrics": "metrics_config.json"},
    )
    assert result[1] == (
        "report",
        {"clusters": "prod-clusters.txt", "metrics": "metrics_config.json"},
    )


def test_parse_pipeline_optional_output_dir():
    """Report config with output_dir is preserved in config dict."""
    data = {
        "steps": [
            {
                "report": {
                    "clusters": "clusters.txt",
                    "metrics": "metrics_config.json",
                    "output_dir": "out",
                }
            }
        ]
    }
    result = pipeline.parse_pipeline(data)
    assert result[0][1]["output_dir"] == "out"


def test_parse_pipeline_empty_steps_list():
    """Empty steps list is valid and returns empty list."""
    result = pipeline.parse_pipeline({"steps": []})
    assert result == []


# ---- parse_pipeline: invalid cases ----


def test_parse_pipeline_root_not_dict():
    """Root must be a dict."""
    with pytest.raises(ValueError, match="root must be a dict"):
        pipeline.parse_pipeline([])
    with pytest.raises(ValueError, match="root must be a dict"):
        pipeline.parse_pipeline("steps: []")


def test_parse_pipeline_missing_steps_key():
    """Root must have 'steps' key."""
    with pytest.raises(ValueError, match="'steps' key"):
        pipeline.parse_pipeline({})
    with pytest.raises(ValueError, match="'steps' key"):
        pipeline.parse_pipeline({"other": []})


def test_parse_pipeline_steps_not_list():
    """'steps' value must be a list."""
    with pytest.raises(ValueError, match="'steps' must be a list"):
        pipeline.parse_pipeline({"steps": {}})
    with pytest.raises(ValueError, match="'steps' must be a list"):
        pipeline.parse_pipeline({"steps": "not a list"})


def test_parse_pipeline_step_not_dict():
    """Each step must be a dict."""
    with pytest.raises(ValueError, match="must be a dict"):
        pipeline.parse_pipeline({"steps": ["string step"]})
    with pytest.raises(ValueError, match="must be a dict"):
        pipeline.parse_pipeline({"steps": [[{"report": {}}]]})


def test_parse_pipeline_step_zero_keys():
    """Step must have exactly one key (the verb)."""
    with pytest.raises(ValueError, match="exactly one key"):
        pipeline.parse_pipeline({"steps": [{}]})


def test_parse_pipeline_step_two_keys():
    """Step must have exactly one key."""
    with pytest.raises(ValueError, match="exactly one key"):
        pipeline.parse_pipeline(
            {
                "steps": [
                    {
                        "report": {"clusters": "c", "metrics": "m"},
                        "other": {},
                    }
                ]
            }
        )


def test_parse_pipeline_step_value_not_dict():
    """Step value (config) must be a dict."""
    with pytest.raises(ValueError, match="value must be a dict"):
        pipeline.parse_pipeline({"steps": [{"report": "string"}]})
    with pytest.raises(ValueError, match="value must be a dict"):
        pipeline.parse_pipeline({"steps": [{"report": 123}]})


# ---- validate_report_config ----


def test_validate_report_config_valid():
    """Valid config with clusters and metrics does not raise."""
    pipeline.validate_report_config(
        {"clusters": "clusters.txt", "metrics": "metrics_config.json"}
    )


def test_validate_report_config_missing_clusters():
    """Missing or empty 'clusters' raises."""
    with pytest.raises(ValueError, match="'clusters'"):
        pipeline.validate_report_config({"metrics": "m.json"})
    with pytest.raises(ValueError, match="'clusters'"):
        pipeline.validate_report_config(
            {"clusters": "", "metrics": "m.json"}
        )


def test_validate_report_config_missing_metrics():
    """Missing or empty 'metrics' raises."""
    with pytest.raises(ValueError, match="'metrics'"):
        pipeline.validate_report_config({"clusters": "c.txt"})
    with pytest.raises(ValueError, match="'metrics'"):
        pipeline.validate_report_config(
            {"clusters": "c.txt", "metrics": ""}
        )
