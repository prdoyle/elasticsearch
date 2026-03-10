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
Kibana HTTP client: export saved objects. Uses requests; credentials are passed in
so tests can inject mock responses.
"""

from __future__ import annotations

import json
from typing import Any, Iterator

import requests


EXPORT_TIMEOUT_SECONDS = 120


def export_saved_objects(
    base_url: str,
    credentials: dict[str, Any],
    timeout: int = EXPORT_TIMEOUT_SECONDS,
) -> Iterator[dict[str, Any]]:
    """
    POST /api/saved_objects/_export with type ["*"], stream NDJSON and yield each object as a dict.

    credentials: dict with either
      - "headers": dict of HTTP headers (must include kbn-xsrf and optionally Authorization), or
      - "cookies": dict of cookie name -> value (headers will get kbn-xsrf)
    """
    url = base_url.rstrip("/") + "/api/saved_objects/_export"
    headers = dict(credentials.get("headers") or {})
    if "kbn-xsrf" not in headers and "kbn-xsrf" not in (k.lower() for k in headers):
        headers["kbn-xsrf"] = "true"
    cookies = credentials.get("cookies")
    payload = {"type": ["*"], "excludeExportDetails": True}

    with requests.post(
        url,
        json=payload,
        headers=headers,
        cookies=cookies,
        timeout=timeout,
        stream=True,
    ) as resp:
        resp.raise_for_status()
        for line in resp.iter_lines(decode_unicode=True):
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(obj, dict) and ("type" in obj or "attributes" in obj):
                yield obj
