"""upstream은 httpx.MockTransport로 흉내 낸다. 외부 서비스 없이 `uv run pytest`로 돈다."""

import tempfile
import time

import httpx
import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from app import create_app
from settings import Settings

_key = ec.generate_private_key(ec.SECP256R1())
_other_key = ec.generate_private_key(ec.SECP256R1())
with tempfile.NamedTemporaryFile("wb", suffix=".pem", delete=False) as f:
    f.write(
        _key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )

calls: list[httpx.Request] = []


def upstream(request: httpx.Request) -> httpx.Response:
    calls.append(request)
    if request.url.host == "down":
        raise httpx.ConnectError("connection refused")
    return httpx.Response(
        200,
        json={
            "host": request.url.host,
            "path": request.url.path,
            "query": request.url.query.decode(),
            "body": request.content.decode(),
        },
    )


def gateway(**overrides) -> TestClient:
    settings = Settings(
        **{
            "upstreams": ("http://up1", "http://up2"),
            "public_key_file": f.name,
            **overrides,
        }
    )
    return TestClient(create_app(settings, httpx.MockTransport(upstream)))


def bearer(key=_key, aud="turnstile:access", iss="turnstile", sub="1", ttl=60) -> dict:
    claims = {"iss": iss, "aud": aud, "sub": sub, "exp": int(time.time()) + ttl}
    return {"Authorization": f"Bearer {jwt.encode(claims, key, algorithm='ES256')}"}


def test_rejects_requests_without_valid_token():
    calls.clear()
    with gateway() as c:
        invalid = [
            {},
            {"Authorization": "Bearer garbage"},
            bearer(aud="other"),
            bearer(iss="other"),
            bearer(key=_other_key),
            bearer(ttl=-10),
        ]
        for headers in invalid:
            res = c.get("/api/v1/concerts/seats", headers=headers)
            assert res.status_code == 401, headers
            assert res.headers["www-authenticate"] == "Bearer"
    assert calls == []


def test_forwards_valid_requests_round_robin():
    with gateway() as c:
        first = c.get("/api/v1/concerts/seats?page=2", headers=bearer()).json()
        second = c.post(
            "/api/v1/reservation", headers=bearer(), json={"seatId": 1}
        ).json()
    assert first == {
        "host": "up1",
        "path": "/api/v1/concerts/seats",
        "query": "page=2",
        "body": "",
    }
    assert (second["host"], second["path"], second["body"]) == (
        "up2",
        "/api/v1/reservation",
        '{"seatId":1}',
    )


def test_rate_limits_each_token_separately():
    with gateway(rate_per_sec=0.001, burst=2) as c:
        statuses = [
            c.get("/api/v1/x", headers=bearer(sub="7")).status_code for _ in range(3)
        ]
        other = c.get("/api/v1/x", headers=bearer(sub="8")).status_code
    assert statuses == [200, 200, 429]
    assert other == 200


def test_upstream_down_returns_502():
    with gateway(upstreams=("http://down",)) as c:
        assert c.get("/api/v1/x", headers=bearer()).status_code == 502
