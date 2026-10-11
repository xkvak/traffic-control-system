"""좌석 예매 부하 시나리오. 대기열을 거치는 경우와 거치지 않는 경우를 비교한다.

    uvx --from locust==2.46.7 --with "pyjwt[crypto]" locust -f examples/locust/locustfile.py QueueVisitor
    uvx --from locust==2.46.7 --with "pyjwt[crypto]" locust -f examples/locust/locustfile.py DirectVisitor

- QueueVisitor: 대기열 입장 → 차례가 오면 좌석 조회·예약 → 퇴장. 한 바퀴 돌면 새 방문자로 다시 줄을 선다
- DirectVisitor: 대기열 없이 매초 새 방문자가 좌석 조회·예약 (토큰은 대기열 개인키로 직접 서명)
- 사용자가 많으면 --processes로 나눌 것: 프로세스당 소켓이 1,024개를 넘으면 연결을 재사용하지 못한다
"""

import os
import random
import time
import uuid
from pathlib import Path

import gevent
import jwt
from cryptography.hazmat.primitives.serialization import load_pem_private_key
from locust import FastHttpUser, constant_pacing, task

QUEUE_URL = os.environ.get("QUEUE_URL", "http://localhost:8083")
PRIVATE_KEY = Path(__file__).resolve().parents[2] / "turnstile/keys/private.pem"


class SeatBooking(FastHttpUser):
    abstract = True
    host = "http://localhost:8080"  # gateway

    def book(self, token: str) -> None:
        auth = {"Authorization": f"Bearer {token}"}
        r = self.client.get("/api/v1/concerts/seats", headers=auth)
        available = [s["id"] for s in r.json() if s["status"] == "AVAILABLE"] if r.status_code == 200 else []
        if available:
            body = {"bookerName": f"locust-{random.getrandbits(32)}", "seatId": random.choice(available)}
            with self.client.post("/api/v1/reservation", json=body, headers=auth, catch_response=True) as res:
                if res.status_code == 400:  # 그 사이 다른 방문자가 먼저 예약: 정상 응답
                    res.success()


class QueueVisitor(SeatBooking):
    @task
    def visit(self):
        self.client.cookiejar.clear()  # 새 방문자: ticket 쿠키 없이 줄을 선다
        token = self.wait_in_queue()
        if token is None:
            return
        self.book(token)
        self.client.post(f"{QUEUE_URL}/queue/leave", headers={"Authorization": f"Bearer {token}"}, name="/queue/leave")

    def wait_in_queue(self) -> str | None:
        path = "/queue/enter"
        while True:
            method = "POST" if path == "/queue/enter" else "GET"
            with self.client.request(method, QUEUE_URL + path, name=path, catch_response=True) as r:
                if r.status_code != 200:  # 연결 오류는 status 0이라 r.ok가 True가 된다
                    return None
                body = r.json()
                if body["status"] == "ALLOWED":
                    return body["token"]
                if body["status"] != "WAITING":  # FULL·CLOSED·EXPIRED: 입장 실패로 집계
                    r.failure(body["status"])
                    return None
            gevent.sleep(body["retryAfterMs"] / 1000)  # 서버가 정해 준 간격대로 폴링
            path = "/queue/status"


class DirectVisitor(SeatBooking):
    wait_time = constant_pacing(1)  # 사용자 1명이 1초에 방문자 1명: 사용자 수 = 초당 방문자 수

    def on_start(self):
        self.key = load_pem_private_key(PRIVATE_KEY.read_bytes(), None)

    @task
    def visit(self):
        claims = {"iss": "turnstile", "aud": "turnstile:access", "sub": uuid.uuid4().hex, "exp": int(time.time()) + 300}
        self.book(jwt.encode(claims, self.key, algorithm="ES256"))
