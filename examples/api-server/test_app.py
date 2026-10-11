"""로컬 MySQL(localhost:3306)에 concert_test DB를 만들어 돌리는 테스트. 끝나면 DB를 지운다.

`docker compose up -d mysql` 후 `uv run pytest`.
"""

import asyncio
import os

import pymysql
import pytest
from fastapi.testclient import TestClient

from app import create_app
from settings import Settings
from store import ReservationError

SETTINGS = Settings(
    mysql_host=os.environ.get("TEST_MYSQL_HOST", "localhost"),
    mysql_database="concert_test",
    seat_count=5,
    pool_size=10,
)
SEATS = "/api/v1/concerts/seats"
RESERVATION = "/api/v1/reservation"


def admin():
    return pymysql.connect(
        host=SETTINGS.mysql_host,
        port=SETTINGS.mysql_port,
        user=SETTINGS.mysql_user,
        password=SETTINGS.mysql_password,
        autocommit=True,
    )


@pytest.fixture(scope="module")
def client():
    with admin() as conn, conn.cursor() as cur:
        cur.execute("DROP DATABASE IF EXISTS concert_test")
        cur.execute("CREATE DATABASE concert_test")
    with TestClient(create_app(SETTINGS)) as c:
        yield c
    with admin() as conn, conn.cursor() as cur:
        cur.execute("DROP DATABASE concert_test")


@pytest.fixture
def c(client):
    assert client.delete(SEATS).status_code == 204
    return client


def seat_ids(c):
    return [seat["id"] for seat in c.get(SEATS).json()]


def test_seeds_seats_once(c):
    seats = c.get(SEATS).json()
    assert [s["seatNo"] for s in seats] == [f"S-{n:04d}" for n in range(1, 6)]
    assert {s["status"] for s in seats} == {"AVAILABLE"}
    c.portal.call(c.app.state.store.initialize, 5)
    assert len(c.get(SEATS).json()) == 5


def test_reserve_and_reject_double_booking(c):
    seat_id = seat_ids(c)[0]
    res = c.post(RESERVATION, json={"bookerName": " 홍길동 ", "seatId": seat_id})
    assert res.status_code == 200 and res.text.isdigit()
    assert c.get(SEATS).json()[0]["status"] == "RESERVED"
    again = c.post(RESERVATION, json={"bookerName": "임꺽정", "seatId": seat_id})
    assert (again.status_code, again.text) == (
        400,
        "This seat has already been reserved",
    )


def test_reservation_validation(c):
    cases = [
        ({"bookerName": "  ", "seatId": 1}, "예매자 이름을 입력해 주세요."),
        (
            {"bookerName": "a" * 101, "seatId": 1},
            "예매자 이름은 100자 이하로 입력해 주세요.",
        ),
        (
            {"bookerName": "a", "seatId": 999_999},
            "There is no seat available for this seat",
        ),
        ({"bookerName": "a"}, "There is no seat available for this seat"),
    ]
    for body, message in cases:
        res = c.post(RESERVATION, json=body)
        assert (res.status_code, res.text) == (400, message), body


def test_concurrent_reservations_allow_only_one(c):
    seat_id = seat_ids(c)[0]
    store = c.app.state.store

    async def attempt(i):
        try:
            await store.reserve(f"user-{i}", seat_id)
            return True
        except ReservationError:
            return False

    async def race():
        return await asyncio.gather(*(attempt(i) for i in range(10)))

    assert sum(c.portal.call(race)) == 1


def test_reset_reopens_seats(c):
    for seat_id in seat_ids(c)[:2]:
        assert (
            c.post(RESERVATION, json={"bookerName": "a", "seatId": seat_id}).status_code
            == 200
        )
    assert c.delete(SEATS).status_code == 204
    assert {s["status"] for s in c.get(SEATS).json()} == {"AVAILABLE"}
    with admin() as conn, conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM concert_test.reservation")
        assert cur.fetchone()[0] == 0

