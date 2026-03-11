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

from typing import Any


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


def parse_pipeline(yaml_data: Any) -> list[tuple[str, dict[str, Any]]]:
    """
    Parse pipeline preset structure into a list of (verb, config) steps.

    Expects yaml_data to be a dict with key "steps" whose value is a list.
    Each step must be a dict with exactly one key (the verb); the value must be a dict (the config).
    Raises ValueError if the structure is invalid.
    """
    if not isinstance(yaml_data, dict):
        raise ValueError("pipeline root must be a dict with a 'steps' key")
    if "steps" not in yaml_data:
        raise ValueError("pipeline must have a 'steps' key")
    steps_raw = yaml_data["steps"]
    if not isinstance(steps_raw, list):
        raise ValueError("pipeline 'steps' must be a list")
    result = []
    for i, item in enumerate(steps_raw):
        if not isinstance(item, dict):
            raise ValueError(f"pipeline step at index {i} must be a dict")
        if len(item) != 1:
            raise ValueError(
                f"pipeline step at index {i} must have exactly one key (the verb), got {len(item)}"
            )
        verb = next(iter(item.keys()))
        config = item[verb]
        if not isinstance(config, dict):
            raise ValueError(f"pipeline step at index {i} verb '{verb}' value must be a dict")
        result.append((verb, config))
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
