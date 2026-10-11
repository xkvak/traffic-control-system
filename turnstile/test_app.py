"""실제 Redis(db 15)에 대고 도는 테스트. `docker compose up -d redis` 후 `uv run pytest`."""

import os
import tempfile
import time

import jwt
import pytest
import redis
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from app import COOKIE, create_app
from settings import Settings
from store import K

_key = ec.generate_private_key(ec.SECP256R1())
with tempfile.NamedTemporaryFile("wb", suffix=".pem", delete=False) as f:
    f.write(
        _key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
SETTINGS = Settings(
    private_key_file=f.name,
    redis_url=os.environ.get("TEST_REDIS_URL", "redis://localhost:6379/15"),
    cookie_secure=False,
    admin_token="secret",
    dispatch_interval=3600,
)
PUBLIC_PEM = _key.public_key().public_bytes(
    serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
)
SEQ, WAITING, PENDING, ACTIVE, CONFIG, DEADLINE = (
    K[k] for k in ("seq", "waiting", "pending", "active", "config", "deadline")
)
db = redis.Redis.from_url(SETTINGS.redis_url, decode_responses=True)


@pytest.fixture(scope="module")
def client():
    with TestClient(create_app(SETTINGS)) as c:
        yield c


@pytest.fixture
def c(client):
    db.flushdb()
    dispatch(client)
    return client


def dispatch(c):
    return c.portal.call(c.app.state.turnstile.dispatch_once)


def call(c, method, path, ticket=None, headers=None, **kw):
    c.cookies.clear()
    headers = dict(headers or {})
    if ticket:
        headers["Cookie"] = f"{COOKIE}={ticket}"
    return c.request(method, path, headers=headers, **kw)


def enter(c, ticket=None):
    res = call(c, "POST", "/queue/enter", ticket)
    return res.json(), res.cookies.get(COOKIE, ticket)


def status(c, ticket):
    return call(c, "GET", "/queue/status", ticket).json()


def now_ms():
    return int(time.time() * 1000)


def token_exp(body):
    return jwt.decode(body["token"], options={"verify_signature": False})["exp"]


def refresh(c, token):
    return call(
        c, "POST", "/queue/refresh", headers={"Authorization": f"Bearer {token}"}
    )


def test_fast_path_token_verifies_with_public_key_only(c):
    body, ticket = enter(c)
    assert body["status"] == "ALLOWED"
    claims = jwt.decode(
        body["token"], PUBLIC_PEM, algorithms=["ES256"], audience="turnstile:access"
    )
    assert claims["iss"] == "turnstile"
    with pytest.raises(jwt.InvalidTokenError):
        jwt.decode(
            ticket, PUBLIC_PEM, algorithms=["ES256"], audience="turnstile:access"
        )


def test_capacity_and_fifo_order(c):
    db.hset(CONFIG, "max_active", 2)
    dispatch(c)
    results = [enter(c)[0] for _ in range(5)]
    assert [b["status"] for b in results] == ["ALLOWED"] * 2 + ["WAITING"] * 3
    assert [b["rank"] for b in results[2:]] == [1, 2, 3]
    assert dispatch(c) == 0
    db.zadd(ACTIVE, {"1": now_ms() - 1000, "2": now_ms() - 1000})
    assert dispatch(c) == 2
    assert db.zrange(PENDING, 0, -1) == ["3", "4"]


def test_admit_rate_caps_inflow(c):
    db.hset(CONFIG, mapping={"max_active": 100, "admit_rate": 2})
    dispatch(c)
    assert [enter(c)[0]["status"] for _ in range(3)] == [
        "ALLOWED",
        "ALLOWED",
        "WAITING",
    ]
    assert dispatch(c) == 0


def test_claim_flow(c):
    db.hset(CONFIG, "state", "paused")
    dispatch(c)
    (_, t1), (_, t2), (_, t3) = enter(c), enter(c), enter(c)
    assert status(c, t1)["status"] == "WAITING"
    db.hset(CONFIG, "state", "open")
    assert dispatch(c) == 3

    first, again = status(c, t1), status(c, t1)
    assert first["status"] == again["status"] == "ALLOWED"
    assert token_exp(first) == token_exp(again)

    db.zadd(PENDING, {"2": now_ms() - 1000})
    assert status(c, t2)["rank"] == 1
    assert db.zscore(WAITING, "2") == 2

    db.zrem(PENDING, "3")
    assert status(c, t3) == {"status": "EXPIRED"}


def test_enter_is_idempotent_with_cookie(c):
    db.hset(CONFIG, "state", "paused")
    dispatch(c)
    body, ticket = enter(c)
    again, _ = enter(c, ticket)
    assert again["rank"] == body["rank"] == 1
    assert db.get(SEQ) == "1"
    assert call(c, "GET", "/queue/status").status_code == 400
    assert call(c, "GET", "/queue/status", ticket="forged").status_code == 400


def test_states_and_waiting_bound(c):
    db.hset(CONFIG, mapping={"state": "paused", "max_waiting": 1})
    dispatch(c)
    _, ticket = enter(c)
    assert enter(c)[0] == {"status": "FULL"}
    assert dispatch(c) == 0
    db.hset(CONFIG, "state", "closed")
    dispatch(c)
    assert enter(c)[0] == {"status": "CLOSED"}
    assert status(c, ticket) == {"status": "CLOSED"}


def test_leave_frees_slot(c):
    body, _ = enter(c)
    res = call(
        c, "POST", "/queue/leave", headers={"Authorization": f"Bearer {body['token']}"}
    )
    assert res.status_code == 204
    assert db.zcard(ACTIVE) == 0
    assert call(c, "POST", "/queue/leave").status_code == 401


def test_admin_config_validation(c):
    def put(body, token="secret"):
        return call(
            c,
            "PUT",
            "/admin/config",
            json=body,
            headers={"Authorization": f"Bearer {token}"},
        )

    assert put({"state": "paused"}, token="wrong").status_code == 401
    for bad in [
        {},
        [1],
        {"max_active": -1},
        {"max_active": "5"},
        {"max_active": True},
        {"state": "x"},
        {"foo": 1},
    ]:
        assert put(bad).status_code == 422, bad
    assert put({"max_active": 5, "state": "paused"}).json() == {
        "max_active": "5",
        "state": "paused",
    }


def test_refresh_extends_session_up_to_max(c):
    body, _ = enter(c)
    assert body["refreshAfterMs"] == 20_000
    db.zadd(ACTIVE, {"1": now_ms() + 1000})
    renewed = refresh(c, body["token"]).json()
    assert renewed["status"] == "ALLOWED"
    assert db.zscore(ACTIVE, "1") > now_ms() + 50_000
    assert token_exp(renewed) == int(db.zscore(ACTIVE, "1")) // 1000

    cap = now_ms() + 5000
    db.zadd(ACTIVE, {"1": now_ms() + 1000})
    db.zadd(DEADLINE, {"1": cap})
    assert token_exp(refresh(c, body["token"]).json()) == cap // 1000


def test_refresh_rejected_after_leave_or_expiry(c):
    (a, _), (b, _) = enter(c), enter(c)
    call(c, "POST", "/queue/leave", headers={"Authorization": f"Bearer {a['token']}"})
    assert refresh(c, a["token"]).json() == {"status": "EXPIRED"}
    db.zadd(ACTIVE, {"2": now_ms() - 1000})
    assert refresh(c, b["token"]).json() == {"status": "EXPIRED"}
    assert refresh(c, "forged").status_code == 401


def test_dispatch_does_not_wait_for_request_pool(c):
    pool = c.app.state.turnstile._store._redis.connection_pool
    held = [c.portal.call(pool.get_connection) for _ in range(pool.max_connections)]
    try:
        dispatch(c)  # 요청 풀이 바닥나도 디스패처는 전용 연결로 돈다 (같은 풀이면 1초 뒤 ConnectionError)
    finally:
        for conn in held:
            c.portal.call(pool.release, conn)
