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

"""Unit tests for auth module pure logic: cluster_slug, credential builders, cache validation."""

import re
from datetime import datetime, timezone, timedelta

import pytest

import auth


# ---- cluster_slug ----


def test_cluster_slug_host_like_base_and_hex_suffix():
    slug = auth.cluster_slug("https://platform-metrics.kb.ca-central-1.aws.elastic-cloud.com/app/home")
    # Base: host part, invalid chars -> _
    assert re.match(r"^[a-z0-9_.-]+_[0-9a-f]{16}$", slug) is not None
    assert "platform_metrics_kb_ca_central_1_aws_elastic_cloud_com" in slug or slug.startswith("platform")


def test_cluster_slug_very_long_url_truncated():
    long_host = "a" * 150 + ".example.com"
    url = "https://" + long_host
    slug = auth.cluster_slug(url)
    # Base truncated to 100, then _ and 16 hex
    parts = slug.rsplit("_", 1)
    assert len(parts) == 2
    assert len(parts[0]) == 100
    assert len(parts[1]) == 16
    assert all(c in "0123456789abcdef" for c in parts[1])


def test_cluster_slug_two_urls_different_slugs():
    slug1 = auth.cluster_slug("https://cluster-a.example.com")
    slug2 = auth.cluster_slug("https://cluster-b.example.com")
    assert slug1 != slug2


def test_cluster_slug_same_url_deterministic():
    url = "https://foo.kb.region.aws.elastic-cloud.com"
    assert auth.cluster_slug(url) == auth.cluster_slug(url)


# ---- get_credentials_from_api_key ----


def test_get_credentials_from_api_key():
    out = auth.get_credentials_from_api_key("my-api-key")
    assert out["headers"]["Authorization"] == "ApiKey my-api-key"
    assert out["headers"]["kbn-xsrf"] == "true"


# ---- get_credentials_from_cookies ----


def test_get_credentials_from_cookies():
    cookies = {"sid": "abc123", "other": "value"}
    out = auth.get_credentials_from_cookies(cookies)
    assert out["headers"]["kbn-xsrf"] == "true"
    assert out["cookies"] == cookies


# ---- parse_and_validate_cached_credentials ----


def test_parse_and_validate_cached_credentials_valid_within_ttl():
    now = datetime.now(timezone.utc)
    created = (now - timedelta(seconds=60)).isoformat()
    data = {"created": created, "credentials": {"headers": {"Authorization": "ApiKey x"}}}
    result = auth.parse_and_validate_cached_credentials(data, now, ttl_seconds=3600)
    assert result == {"headers": {"Authorization": "ApiKey x"}}


def test_parse_and_validate_cached_credentials_past_ttl():
    now = datetime.now(timezone.utc)
    created = (now - timedelta(seconds=4000)).isoformat()
    data = {"created": created, "credentials": {"headers": {}}}
    result = auth.parse_and_validate_cached_credentials(data, now, ttl_seconds=3600)
    assert result is None


def test_parse_and_validate_cached_credentials_missing_created():
    data = {"credentials": {"headers": {}}}
    result = auth.parse_and_validate_cached_credentials(
        data, datetime.now(timezone.utc), ttl_seconds=3600
    )
    assert result is None


def test_parse_and_validate_cached_credentials_missing_credentials():
    data = {"created": datetime.now(timezone.utc).isoformat()}
    result = auth.parse_and_validate_cached_credentials(
        data, datetime.now(timezone.utc), ttl_seconds=3600
    )
    assert result is None


def test_parse_and_validate_cached_credentials_credentials_not_dict():
    now = datetime.now(timezone.utc)
    data = {"created": now.isoformat(), "credentials": "not-a-dict"}
    result = auth.parse_and_validate_cached_credentials(data, now, ttl_seconds=3600)
    assert result is None


def test_parse_and_validate_cached_credentials_invalid_created_string():
    data = {"created": "not-a-date", "credentials": {"headers": {}}}
    result = auth.parse_and_validate_cached_credentials(
        data, datetime.now(timezone.utc), ttl_seconds=3600
    )
    assert result is None


def test_parse_and_validate_cached_credentials_boundary_exactly_ttl_returns_credentials():
    """At now == created + ttl_seconds, still valid (not expired)."""
    now = datetime.now(timezone.utc)
    created = now - timedelta(seconds=3600)
    data = {"created": created.isoformat(), "credentials": {"cookies": {"sid": "x"}}}
    result = auth.parse_and_validate_cached_credentials(data, now, ttl_seconds=3600)
    assert result is not None
    assert result == {"cookies": {"sid": "x"}}


def test_parse_and_validate_cached_credentials_just_over_ttl_returns_none():
    now = datetime.now(timezone.utc)
    created = now - timedelta(seconds=3601)
    data = {"created": created.isoformat(), "credentials": {"headers": {}}}
    result = auth.parse_and_validate_cached_credentials(data, now, ttl_seconds=3600)
    assert result is None


# ---- Corner cases ----


def test_cluster_slug_empty_string():
    """Empty URL produces a slug (no crash); may be degenerate."""
    slug = auth.cluster_slug("")
    assert isinstance(slug, str)
    assert len(slug) >= 1


def test_parse_and_validate_cached_credentials_created_in_future_returns_none():
    """Cache entry with created in the future is treated as invalid."""
    now = datetime.now(timezone.utc)
    created = now + timedelta(seconds=3600)
    data = {"created": created.isoformat(), "credentials": {"headers": {"Authorization": "ApiKey x"}}}
    result = auth.parse_and_validate_cached_credentials(data, now, ttl_seconds=3600)
    assert result is None


def test_parse_and_validate_cached_credentials_ttl_zero_expires_immediately():
    """ttl_seconds=0 means cache entry is always expired unless now == created."""
    now = datetime.now(timezone.utc)
    created = now - timedelta(seconds=1)
    data = {"created": created.isoformat(), "credentials": {"headers": {"Authorization": "ApiKey x"}}}
    result = auth.parse_and_validate_cached_credentials(data, now, ttl_seconds=0)
    assert result is None


def test_cluster_slug_no_scheme():
    """URL with no scheme (e.g. host-only) should not crash."""
    slug = auth.cluster_slug("foo.kb.region.aws.elastic-cloud.com")
    assert isinstance(slug, str)
    assert re.match(r"^[a-z0-9_.-]+_[0-9a-f]{16}$", slug) is not None
