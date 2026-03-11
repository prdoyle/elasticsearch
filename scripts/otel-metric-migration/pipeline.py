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
Pure logic for parsing config YAML (environments + metrics) and path helpers.
No I/O; all functions take and return in-memory data for testability.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import core

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


def step_output_dir(
    script_dir: Path, base: str, run_timestamp: str, step_name: str
) -> Path:
    """
    Return the path used for a step's output directory: script_dir / base / run_timestamp / step_name.
    No I/O; pure path construction for testability.
    """
    return script_dir / base / run_timestamp / step_name


def parse_config(data: Any) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    """
    Validate config dict and return (metrics_list, environments_map).

    Config must have "environments" (dict, non-empty) and "metrics" (list).
    metrics_list is validated by core.parse_metrics_config. environments keys are env names.
    Raises ValueError if structure is invalid.
    """
    if not isinstance(data, dict):
        raise ValueError("config must be a dict with 'environments' and 'metrics'")
    if "environments" not in data:
        raise ValueError("config must have an 'environments' key")
    if "metrics" not in data:
        raise ValueError("config must have a 'metrics' key")
    environments = data["environments"]
    if not isinstance(environments, dict) or not environments:
        raise ValueError("config 'environments' must be a non-empty dict")
    metrics_raw = data["metrics"]
    metrics_list = core.parse_metrics_config(metrics_raw)
    return (metrics_list, environments)


def get_env_config(environments: dict[str, Any], env: str) -> dict[str, Any]:
    """
    Return the config dict for the given env. Validates env name and that env exists.
    Raises ValueError if env is invalid or missing.
    """
    validate_step_name(env)
    if env not in environments:
        raise ValueError(f"unknown environment '{env}'")
    return environments[env]


def validate_env_config(env_config: dict[str, Any]) -> None:
    """
    Validate that env config has required keys for the report verb (non-empty 'clusters').
    Raises ValueError if invalid.
    """
    if not isinstance(env_config.get("clusters"), str) or not env_config["clusters"]:
        raise ValueError("environment config must have non-empty 'clusters'")
