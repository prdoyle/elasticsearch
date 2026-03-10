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
Credential resolution for Kibana: API key file, cache (with TTL), or browser (Playwright).
"""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import core

CACHE_TTL_SECONDS = 45 * 60  # 45 minutes

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


def _cache_path(cache_dir: str, kibana_url: str) -> Path:
    return Path(cache_dir) / f"{cluster_slug(kibana_url)}.json"


def parse_and_validate_cached_credentials(
    data: dict[str, Any],
    now: datetime,
    ttl_seconds: int,
) -> dict[str, Any] | None:
    """
    Validate cache payload and TTL. Returns credentials dict if valid and not expired, else None.
    Pure: no I/O. data should have "created" (ISO8601 string) and "credentials" (dict).
    """
    created_str = data.get("created")
    credentials = data.get("credentials")
    if not created_str or not isinstance(credentials, dict):
        return None
    try:
        created = datetime.fromisoformat(created_str.replace("Z", "+00:00"))
        if created.tzinfo is None:
            created = created.replace(tzinfo=timezone.utc)
        if (now - created).total_seconds() > ttl_seconds:
            return None
    except (ValueError, TypeError):
        return None
    return credentials


def load_cached_credentials(cache_dir: str, kibana_url: str, ttl_seconds: int = CACHE_TTL_SECONDS) -> dict[str, Any] | None:
    """
    Load credentials from cache if present and not expired.
    Returns None if no cache or expired. Cache file format: { "created": "ISO8601", "credentials": {...} }.
    """
    path = _cache_path(cache_dir, kibana_url)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return None
    return parse_and_validate_cached_credentials(data, datetime.now(timezone.utc), ttl_seconds)


def invalidate_cached_credentials(cache_dir: str, kibana_url: str) -> None:
    """Remove cached credentials for this cluster (e.g. after 401)."""
    path = _cache_path(cache_dir, kibana_url)
    if path.exists():
        path.unlink()


def save_cached_credentials(cache_dir: str, kibana_url: str, credentials: dict[str, Any]) -> None:
    """Write credentials to cache with current timestamp."""
    path = Path(cache_dir)
    path.mkdir(parents=True, exist_ok=True)
    data = {
        "created": datetime.now(timezone.utc).isoformat(),
        "credentials": credentials,
    }
    _cache_path(cache_dir, kibana_url).write_text(json.dumps(data, indent=2))


def get_credentials_from_api_key(api_key: str) -> dict[str, Any]:
    """Build credentials dict for Kibana API using an API key."""
    return {
        "headers": {
            "Authorization": f"ApiKey {api_key}",
            "kbn-xsrf": "true",
        },
    }


def get_credentials_from_cookies(cookies: dict[str, str]) -> dict[str, Any]:
    """Build credentials dict for Kibana API using session cookies."""
    return {
        "headers": {"kbn-xsrf": "true"},
        "cookies": cookies,
    }


def _get_credentials_via_browser(kibana_url: str) -> dict[str, Any]:
    """
    Open browser to Kibana URL; user logs in via Okta; return credentials (cookies).
    Uses Playwright. Blocks until user has logged in and Kibana has loaded.
    """
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as e:
        raise RuntimeError(
            "Playwright is required for browser auth. Install with: pip install playwright && playwright install chromium"
        ) from e

    base_url = core.normalize_kibana_url(kibana_url)
    origin = base_url.rstrip("/")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False)
        context = browser.new_context()
        page = context.new_page()
        page.goto(base_url, wait_until="domcontentloaded", timeout=60000)
        # Wait until we're back on Kibana (user may be redirected to Okta first)
        # Poll for URL to be kibana origin or for a common Kibana app element
        page.wait_for_timeout(2000)
        for _ in range(120):  # up to 2 minutes
            if origin in page.url or page.url.startswith(origin):
                break
            page.wait_for_timeout(1000)
        else:
            browser.close()
            raise RuntimeError("Timed out waiting for Kibana to load after login. Ensure you completed Okta login.")
        # Collect cookies for the Kibana origin
        cookies_list = context.cookies()
        cookie_dict = {c["name"]: c["value"] for c in cookies_list if c.get("domain") in origin or origin.endswith(c.get("domain", ""))}
        if not cookie_dict:
            cookie_dict = {c["name"]: c["value"] for c in cookies_list}
        browser.close()
    return get_credentials_from_cookies(cookie_dict)


def get_credentials(
    kibana_url: str,
    credentials_map: dict[str, str] | None,
    cache_dir: str | None,
    use_browser: bool,
    ttl_seconds: int = CACHE_TTL_SECONDS,
) -> dict[str, Any]:
    """
    Resolve credentials for the given Kibana URL.

    Order: credentials_map (API key) -> cache (if valid) -> browser (if use_browser).
    Returns credentials dict suitable for kibana_client.export_saved_objects.
    Raises RuntimeError if no credentials could be obtained.
    """
    url = core.normalize_kibana_url(kibana_url)

    if credentials_map and url in credentials_map:
        api_key = credentials_map.get(url)
        if api_key:
            return get_credentials_from_api_key(api_key)

    if cache_dir:
        cached = load_cached_credentials(cache_dir, url, ttl_seconds)
        if cached is not None:
            return cached

    if use_browser:
        creds = _get_credentials_via_browser(url)
        if cache_dir:
            save_cached_credentials(cache_dir, url, creds)
        return creds

    raise RuntimeError(
        f"No credentials for {url}. Provide --credentials with an API key for this cluster, "
        "or run without --no-browser to authenticate via Okta in the browser."
    )
