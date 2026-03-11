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
Credential resolution for Kibana: API key from credentials map only.
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any

import core

# Max length of the slug base (before hash suffix) to stay within filesystem limits.
_SLUG_BASE_MAX_LEN = 100
# Length of hex hash suffix to disambiguate truncated slugs. 16 hex chars = 64 bits.
_SLUG_HASH_LEN = 16


def cluster_slug(kibana_url: str) -> str:
    """
    Return a filesystem-safe slug for the cluster URL: normalized host-like base
    (length-limited) plus a hash of the full URL to avoid collisions.
    Hash collision would cause two clusters to share one cache file (wrong credentials
    used for one of them, typically leading to 401 or rejected requests).
    """
    u = kibana_url.strip().rstrip("/").lower()
    u = re.sub(r"^https?://", "", u)
    base = re.sub(r"[^a-z0-9.-]", "_", u)
    if len(base) > _SLUG_BASE_MAX_LEN:
        base = base[:_SLUG_BASE_MAX_LEN]
    h = hashlib.sha256(kibana_url.encode()).hexdigest()[:_SLUG_HASH_LEN]
    return f"{base}_{h}"


def get_credentials_from_api_key(api_key: str) -> dict[str, Any]:
    """Build credentials dict for Kibana API using an API key."""
    return {
        "headers": {
            "Authorization": f"ApiKey {api_key}",
            "kbn-xsrf": "true",
        },
    }


def get_credentials(
    kibana_url: str,
    credentials_map: dict[str, str] | None,
) -> dict[str, Any]:
    """
    Resolve credentials for the given Kibana URL from the credentials map only.

    Returns credentials dict suitable for kibana_client.export_saved_objects.
    Raises RuntimeError if no API key is available for the URL.
    """
    url = core.normalize_kibana_url(kibana_url)

    if credentials_map and url in credentials_map:
        api_key = credentials_map.get(url)
        if api_key:
            return get_credentials_from_api_key(api_key)

    raise RuntimeError(
        f"No API key for {url}. Run in an interactive terminal to be prompted to create one."
    )
