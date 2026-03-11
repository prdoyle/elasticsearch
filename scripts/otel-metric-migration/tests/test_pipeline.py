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

"""Unit tests for pipeline parsing: parse_pipeline, validate_report_config, resolve_preset_filename, validate_step_name, step_output_dir."""

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


# ---- step_output_dir ----


def test_step_output_dir_default_base():
    """Step output dir is script_dir / base / step_name with default base 'out'."""
    assert pipeline.step_output_dir(Path("/script"), "out", "qa_report") == Path(
        "/script/out/qa_report"
    )


def test_step_output_dir_custom_base():
    """Step output dir with custom base (e.g. from config output_dir)."""
    assert pipeline.step_output_dir(
        Path("/script"), "reports", "prod_report"
    ) == Path("/script/reports/prod_report")


# ---- parse_pipeline: valid cases ----


def test_parse_pipeline_single_step():
    """Single step with step name and do yields one (step_name, verb, config) tuple."""
    data = {
        "steps": [
            {
                "qa_report": {
                    "do": "report",
                    "clusters": "clusters.txt",
                    "metrics": "metrics_config.json",
                }
            }
        ]
    }
    result = pipeline.parse_pipeline(data)
    assert result == [
        (
            "qa_report",
            "report",
            {"clusters": "clusters.txt", "metrics": "metrics_config.json"},
        )
    ]


def test_parse_pipeline_two_steps():
    """Two steps yield two tuples in list order."""
    data = {
        "steps": [
            {
                "qa_report": {
                    "do": "report",
                    "clusters": "qa-clusters.txt",
                    "metrics": "metrics_config.json",
                }
            },
            {
                "prod_report": {
                    "do": "report",
                    "clusters": "prod-clusters.txt",
                    "metrics": "metrics_config.json",
                }
            },
        ]
    }
    result = pipeline.parse_pipeline(data)
    assert len(result) == 2
    assert result[0] == (
        "qa_report",
        "report",
        {"clusters": "qa-clusters.txt", "metrics": "metrics_config.json"},
    )
    assert result[1] == (
        "prod_report",
        "report",
        {"clusters": "prod-clusters.txt", "metrics": "metrics_config.json"},
    )


def test_parse_pipeline_optional_output_dir():
    """Report config with output_dir is preserved in config dict."""
    data = {
        "steps": [
            {
                "qa_report": {
                    "do": "report",
                    "clusters": "clusters.txt",
                    "metrics": "metrics_config.json",
                    "output_dir": "out",
                }
            }
        ]
    }
    result = pipeline.parse_pipeline(data)
    assert result[0][2]["output_dir"] == "out"


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
    """Step must have exactly one key (the step name)."""
    with pytest.raises(ValueError, match="exactly one key"):
        pipeline.parse_pipeline({"steps": [{}]})


def test_parse_pipeline_step_two_keys():
    """Step must have exactly one key."""
    with pytest.raises(ValueError, match="exactly one key"):
        pipeline.parse_pipeline(
            {
                "steps": [
                    {
                        "qa_report": {"do": "report", "clusters": "c", "metrics": "m"},
                        "other": {"do": "report"},
                    }
                ]
            }
        )


def test_parse_pipeline_step_value_not_dict():
    """Step value must be a dict."""
    with pytest.raises(ValueError, match="value must be a dict"):
        pipeline.parse_pipeline({"steps": [{"qa_report": "string"}]})
    with pytest.raises(ValueError, match="value must be a dict"):
        pipeline.parse_pipeline({"steps": [{"qa_report": 123}]})


def test_parse_pipeline_step_missing_do():
    """Step value must have 'do' key."""
    with pytest.raises(ValueError, match="must have a 'do' key"):
        pipeline.parse_pipeline(
            {"steps": [{"qa_report": {"clusters": "c.txt", "metrics": "m.json"}}]}
        )


def test_parse_pipeline_step_do_not_string():
    """Step value 'do' must be a non-empty string."""
    with pytest.raises(ValueError, match="'do' must be a non-empty string"):
        pipeline.parse_pipeline(
            {"steps": [{"qa_report": {"do": "", "clusters": "c", "metrics": "m"}}]}
        )
    with pytest.raises(ValueError, match="'do' must be a non-empty string"):
        pipeline.parse_pipeline(
            {"steps": [{"qa_report": {"do": 123, "clusters": "c", "metrics": "m"}}]}
        )


def test_parse_pipeline_invalid_step_name():
    """Invalid step name (e.g. contains space) raises."""
    with pytest.raises(ValueError, match="non-empty and contain only"):
        pipeline.parse_pipeline(
            {
                "steps": [
                    {
                        "bad name": {
                            "do": "report",
                            "clusters": "c.txt",
                            "metrics": "m.json",
                        }
                    }
                ]
            }
        )


def test_parse_pipeline_duplicate_step_name():
    """Duplicate step names raise."""
    with pytest.raises(ValueError, match="duplicate step name"):
        pipeline.parse_pipeline(
            {
                "steps": [
                    {"qa_report": {"do": "report", "clusters": "c", "metrics": "m"}},
                    {"qa_report": {"do": "report", "clusters": "c2", "metrics": "m"}},
                ]
            }
        )


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
