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
Main entry point for otel-metric-migration: run a pipeline of verbs from a YAML preset.
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime, timezone
from pathlib import Path

import yaml

import pipeline
import report_metric_references


def _report_handler(
    config: dict[str, object],
    script_dir: Path,
    step_name: str,
    run_timestamp: str,
) -> int:
    """Run the report verb: load clusters/metrics, credentials, call run()."""
    pipeline.validate_report_config(config)
    clusters_path = script_dir / str(config["clusters"])
    metrics_path = script_dir / str(config["metrics"])
    base = str(config.get("output_dir", "out"))
    output_dir_base = script_dir / base
    run_dir = pipeline.step_output_dir(
        script_dir, base, run_timestamp, step_name
    )
    api_keys_path = script_dir / "api-keys.json"

    try:
        clusters = report_metric_references.load_cluster_list(str(clusters_path))
        metrics_config = report_metric_references.load_metrics_config(str(metrics_path))
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
        metrics_config=metrics_config,
        output_dir=str(output_dir_base),
        credentials_map=credentials_map,
        api_keys_path=api_keys_path,
        run_dir=str(run_dir),
        run_timestamp=run_timestamp,
    )


VERB_REGISTRY: dict[str, object] = {
    "report": _report_handler,
}


def main() -> int:
    script_dir = Path(__file__).resolve().parent

    parser = argparse.ArgumentParser(
        description="Run a pipeline from a YAML preset (e.g. omm.py qa-report.yaml or omm.py qa-report)."
    )
    parser.add_argument(
        "preset_file",
        help="Preset filename (e.g. qa-report.yaml or qa-report); loads from the script directory",
    )
    args = parser.parse_args()

    try:
        filename = pipeline.resolve_preset_filename(args.preset_file)
    except ValueError as e:
        print(e, file=sys.stderr)
        return 1
    preset_path = script_dir / filename
    if not preset_path.exists():
        print(f"Preset file not found: {preset_path}", file=sys.stderr)
        return 1

    try:
        raw = preset_path.read_text()
        loaded = yaml.safe_load(raw)
    except Exception as e:
        print(f"Error loading preset: {e}", file=sys.stderr)
        return 1

    try:
        steps = pipeline.parse_pipeline(loaded)
    except ValueError as e:
        print(f"Invalid pipeline: {e}", file=sys.stderr)
        return 1

    run_timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    for step_name, verb, config in steps:
        if verb not in VERB_REGISTRY:
            print(f"Unknown verb: {verb}", file=sys.stderr)
            return 1
        handler = VERB_REGISTRY[verb]
        assert callable(handler)
        code = handler(config, script_dir, step_name, run_timestamp)
        if code != 0:
            return code
    return 0


if __name__ == "__main__":
    sys.exit(main())
