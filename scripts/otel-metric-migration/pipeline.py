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
Pure logic for parsing pipeline preset YAML into a sequence of (verb, config) steps.
No I/O; all functions take and return in-memory data for testability.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

STEP_NAME_PATTERN = re.compile(r"^[a-zA-Z0-9_-]+$")


def validate_step_name(step_name: str) -> None:
    """
    Validate that step_name is a non-empty string containing only letters, numbers,
    underscore, and hyphen. Periods are forbidden to avoid path confusion (e.g. '..').
    Raises ValueError with a clear message if invalid.
    """
    if not step_name or not isinstance(step_name, str):
        raise ValueError(
            "Step name must be non-empty and contain only letters, numbers, "
            "underscore, and hyphen"
        )
    if not STEP_NAME_PATTERN.fullmatch(step_name):
        raise ValueError(
            "Step name must be non-empty and contain only letters, numbers, "
            "underscore, and hyphen"
        )


def resolve_preset_filename(preset_file: str) -> str:
    """
    Return the preset filename to use for loading (add .yaml only if not already present).
    Raises ValueError if preset_file contains path separators or '..'.
    """
    if "/" in preset_file or ".." in preset_file:
        raise ValueError("Preset filename must not contain path separators or '..'")
    if not preset_file.endswith(".yaml"):
        return preset_file + ".yaml"
    return preset_file


def step_output_dir(
    script_dir: Path, base: str, run_timestamp: str, step_name: str
) -> Path:
    """
    Return the path used for a step's output directory: script_dir / base / run_timestamp / step_name.
    No I/O; pure path construction for testability.
    """
    return script_dir / base / run_timestamp / step_name


def parse_pipeline(yaml_data: Any) -> list[tuple[str, str, dict[str, Any]]]:
    """
    Parse pipeline preset structure into a list of (step_name, verb, config) steps.

    Expects yaml_data to be a dict with key "steps" whose value is a list.
    Each step must be a dict with exactly one key (the step name); the value must be a dict
    that contains "do" (the verb); the rest of the value is the verb's config.
    Step names must be unique and valid (see validate_step_name).
    Raises ValueError if the structure is invalid.
    """
    if not isinstance(yaml_data, dict):
        raise ValueError("pipeline root must be a dict with a 'steps' key")
    if "steps" not in yaml_data:
        raise ValueError("pipeline must have a 'steps' key")
    steps_raw = yaml_data["steps"]
    if not isinstance(steps_raw, list):
        raise ValueError("pipeline 'steps' must be a list")
    seen_names: set[str] = set()
    result = []
    for i, item in enumerate(steps_raw):
        if not isinstance(item, dict):
            raise ValueError(f"pipeline step at index {i} must be a dict")
        if len(item) != 1:
            raise ValueError(
                f"pipeline step at index {i} must have exactly one key (the step name), got {len(item)}"
            )
        step_name = next(iter(item.keys()))
        validate_step_name(step_name)
        if step_name in seen_names:
            raise ValueError(f"duplicate step name '{step_name}' at index {i}")
        seen_names.add(step_name)
        value = item[step_name]
        if not isinstance(value, dict):
            raise ValueError(
                f"pipeline step at index {i} '{step_name}' value must be a dict"
            )
        if "do" not in value:
            raise ValueError(
                f"pipeline step at index {i} '{step_name}' must have a 'do' key (the verb)"
            )
        verb = value["do"]
        if not isinstance(verb, str) or not verb:
            raise ValueError(
                f"pipeline step at index {i} '{step_name}' 'do' must be a non-empty string"
            )
        config = {k: v for k, v in value.items() if k != "do"}
        result.append((step_name, verb, config))
    return result


def validate_report_config(config: dict[str, Any]) -> None:
    """
    Validate that config has required keys for the report verb.
    Raises ValueError if 'clusters' or 'metrics' is missing.
    """
    if not isinstance(config.get("clusters"), str) or not config["clusters"]:
        raise ValueError("report config must have non-empty 'clusters'")
    if not isinstance(config.get("metrics"), str) or not config["metrics"]:
        raise ValueError("report config must have non-empty 'metrics'")
