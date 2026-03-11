#!/usr/bin/env python3
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
Main entry point for otel-metric-migration: omm <env> <verb>.
Loads config.yaml (environments + metrics), dispatches to the requested verb.
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime, timezone
from pathlib import Path

import yaml

import export_saved_objects
import pipeline
import report_metric_references

CONFIG_FILENAME = "config.yaml"
DEFAULT_EXPORT_BATCH_SIZE = 10_000


def _report_handler(
    env_config: dict[str, object],
    script_dir: Path,
    config_dir: Path,
    metrics_list: list[dict[str, object]],
    run_timestamp: str,
    env_name: str,
    config_data: dict[str, object] | None = None,
) -> int:
    """Run the report verb: load clusters, credentials, call run()."""
    pipeline.validate_env_config(env_config)
    clusters_path = config_dir / str(env_config["clusters"])
    base = str(env_config.get("output_dir", "out"))
    output_dir_base = script_dir / base
    run_dir = pipeline.step_output_dir(
        script_dir, base, run_timestamp, env_name, "report"
    )
    api_keys_path = script_dir / "api-keys.json"

    try:
        clusters = report_metric_references.load_cluster_list(str(clusters_path))
    except Exception as e:
        print(f"Error loading config: {e}", file=sys.stderr)
        return 1

    credentials_map = {}
    if api_keys_path.exists():
        try:
            credentials_map = report_metric_references.load_credentials_map(
                str(api_keys_path)
            )
        except Exception as e:
            print(f"Error loading API keys file: {e}", file=sys.stderr)
            return 1

    return report_metric_references.run(
        clusters=clusters,
        metrics_config=metrics_list,
        output_dir=str(output_dir_base),
        credentials_map=credentials_map,
        api_keys_path=api_keys_path,
        run_dir=str(run_dir),
        run_timestamp=run_timestamp,
    )


def _export_handler(
    env_config: dict[str, object],
    script_dir: Path,
    config_dir: Path,
    metrics_list: list[dict[str, object]],
    run_timestamp: str,
    env_name: str,
    config_data: dict[str, object] | None = None,
) -> int:
    """Run the export verb: resolve latest, load report, export saved objects to NDJSON."""
    pipeline.validate_env_config(env_config)
    base = str(env_config.get("output_dir", "out"))
    try:
        latest_dir = pipeline.resolve_latest_run_dir(script_dir, base)
    except ValueError as e:
        print(str(e), file=sys.stderr)
        return 1
    work_dir = latest_dir / env_name
    export_batch_size = DEFAULT_EXPORT_BATCH_SIZE
    if config_data is not None and isinstance(config_data.get("export_batch_size"), int):
        export_batch_size = int(config_data["export_batch_size"])
    api_keys_path = script_dir / "api-keys.json"
    credentials_map = {}
    if api_keys_path.exists():
        try:
            credentials_map = report_metric_references.load_credentials_map(
                str(api_keys_path)
            )
        except Exception as e:
            print(f"Error loading API keys file: {e}", file=sys.stderr)
            return 1
    return export_saved_objects.run(
        work_dir=work_dir,
        credentials_map=credentials_map,
        api_keys_path=api_keys_path,
        export_batch_size=export_batch_size,
    )


VERB_REGISTRY: dict[str, object] = {
    "report": _report_handler,
    "export": _export_handler,
}


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    config_path = script_dir / CONFIG_FILENAME

    parser = argparse.ArgumentParser(
        description="Run a verb for an environment (e.g. omm qa report)."
    )
    parser.add_argument(
        "env",
        help="Environment name (must exist in config.yaml environments)",
    )
    parser.add_argument(
        "verb",
        help="Verb to run (e.g. report)",
    )
    args = parser.parse_args()

    if not config_path.exists():
        print(f"Config file not found: {config_path}", file=sys.stderr)
        return 1

    try:
        raw = config_path.read_text()
        loaded = yaml.safe_load(raw)
    except Exception as e:
        print(f"Error loading config: {e}", file=sys.stderr)
        return 1

    try:
        metrics_list, environments = pipeline.parse_config(loaded)
        env_config = pipeline.get_env_config(environments, args.env)
    except ValueError as e:
        print(f"Invalid config: {e}", file=sys.stderr)
        return 1

    if args.verb not in VERB_REGISTRY:
        print(f"Unknown verb: {args.verb}", file=sys.stderr)
        return 1

    handler = VERB_REGISTRY[args.verb]
    assert callable(handler)
    config_dir = config_path.resolve().parent
    run_timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    code = handler(
        env_config,
        script_dir,
        config_dir,
        metrics_list,
        run_timestamp,
        args.env,
        config_data=loaded,
    )
    return code


if __name__ == "__main__":
    sys.exit(main())
