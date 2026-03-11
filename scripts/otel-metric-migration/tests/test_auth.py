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

"""Unit tests for auth module: cluster_slug, get_credentials_from_api_key, get_credentials."""

import re

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


def test_cluster_slug_empty_string():
    """Empty URL produces a slug (no crash); may be degenerate."""
    slug = auth.cluster_slug("")
    assert isinstance(slug, str)
    assert len(slug) >= 1


def test_cluster_slug_no_scheme():
    """URL with no scheme (e.g. host-only) should not crash."""
    slug = auth.cluster_slug("foo.kb.region.aws.elastic-cloud.com")
    assert isinstance(slug, str)
    assert re.match(r"^[a-z0-9_.-]+_[0-9a-f]{16}$", slug) is not None


# ---- get_credentials_from_api_key ----


def test_get_credentials_from_api_key():
    out = auth.get_credentials_from_api_key("my-api-key")
    assert out["headers"]["Authorization"] == "ApiKey my-api-key"
    assert out["headers"]["kbn-xsrf"] == "true"


# ---- get_credentials ----


def test_get_credentials_url_in_map_returns_api_key_creds():
    credentials_map = {"https://foo.kb.example.com": "secret-key-123"}
    out = auth.get_credentials("https://foo.kb.example.com", credentials_map)
    assert out["headers"]["Authorization"] == "ApiKey secret-key-123"
    assert out["headers"]["kbn-xsrf"] == "true"


def test_get_credentials_url_normalized_for_lookup():
    """URL is normalized (e.g. trailing slash, case) before lookup."""
    credentials_map = {"https://host.com": "key1"}
    out = auth.get_credentials("https://Host.com/", credentials_map)
    assert out["headers"]["Authorization"] == "ApiKey key1"


def test_get_credentials_url_not_in_map_raises():
    with pytest.raises(RuntimeError) as exc_info:
        auth.get_credentials("https://unknown.example.com", {"https://other.com": "key"})
    assert "https://unknown.example.com" in str(exc_info.value) or "unknown" in str(exc_info.value).lower()
    assert "interactive terminal" in str(exc_info.value).lower()


def test_get_credentials_empty_map_raises():
    with pytest.raises(RuntimeError) as exc_info:
        auth.get_credentials("https://foo.com", {})
    assert "interactive terminal" in str(exc_info.value).lower()


def test_get_credentials_none_map_raises():
    with pytest.raises(RuntimeError):
        auth.get_credentials("https://foo.com", None)


def test_get_credentials_empty_key_for_url_raises():
    """URL in map but value is empty string still raises."""
    credentials_map = {"https://foo.com": ""}
    with pytest.raises(RuntimeError) as exc_info:
        auth.get_credentials("https://foo.com", credentials_map)
    assert "interactive terminal" in str(exc_info.value).lower()
