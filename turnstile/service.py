import asyncio
import logging
import random
from typing import Literal

from prometheus_client import Counter, Gauge
from pydantic import BaseModel, Field

from store import Admitted, Expired, Missed, Queued, QueueStore, Rejected
from tokens import TokenIssuer

log = logging.getLogger("turnstile")

WAITING = Gauge("turnstile_waiting", "Users waiting in the queue")
ACTIVE = Gauge("turnstile_active", "Users holding a slot (claimed + awaiting claim)")
EVENTS = Counter("turnstile_events", "Queue events", ["event"])


class InvalidTicket(Exception):
    pass


class QueueStatus(BaseModel):
    status: Literal["ALLOWED", "WAITING", "EXPIRED", "CLOSED", "FULL"]
    rank: int | None = None
    retry_after_ms: int | None = Field(None, serialization_alias="retryAfterMs")
    token: str | None = None
    refresh_after_ms: int | None = Field(None, serialization_alias="refreshAfterMs")


class Turnstile:
    def __init__(self, store: QueueStore, tokens: TokenIssuer, session_ms: int):
        self._store = store
        self._tokens = tokens
        self._refresh_after_ms = session_ms // 3
        self._cursor = 0
        self._state = "open"
        self._rate = 1

    async def enter(self, ticket: str | None) -> tuple[QueueStatus, str | None]:
        seq = self._tokens.read_ticket(ticket)
        if seq is not None:
            current = await self._status(seq)
            if current.status != "EXPIRED":
                return current, None

        result = await self._store.enter()
        if isinstance(result, Rejected):
            return self._present(result), None
        EVENTS.labels("entered").inc()
        if isinstance(result, Admitted):
            EVENTS.labels("admitted").inc()
        return self._present(result), self._tokens.issue_ticket(result.seq)

    async def poll(self, ticket: str | None) -> QueueStatus:
        seq = self._tokens.read_ticket(ticket)
        if seq is None:
            raise InvalidTicket
        return await self._status(seq)

    async def refresh(self, access_token: str) -> QueueStatus:
        claims = self._tokens.verify_access(access_token)
        return self._present(await self._store.refresh(int(claims["sub"])))

    async def leave(self, access_token: str) -> None:
        claims = self._tokens.verify_access(access_token)
        await self._store.release(claims["sub"])

    async def configure(self, values: dict) -> dict:
        return await self._store.update_config(values)

    async def dispatch_once(self) -> int:
        result = await self._store.dispatch()
        self._cursor, self._state, self._rate = result.cursor, result.state, result.rate
        WAITING.set(result.waiting)
        ACTIVE.set(result.used)
        EVENTS.labels("admitted").inc(result.admitted)
        return result.admitted

    async def run_dispatcher(self, interval: float) -> None:
        while True:
            await asyncio.sleep(interval)
            try:
                await self.dispatch_once()
            except Exception:  # Redis 장애 중에도 루프는 살아 있어야 한다
                log.exception("dispatch failed")

    async def _status(self, seq: int) -> QueueStatus:
        if self._state == "closed":
            return QueueStatus(status="CLOSED")
        if seq > self._cursor:  # 아직 차례가 아님: Redis 없이 응답
            return self._waiting(seq - self._cursor)
        return self._present(await self._store.claim(seq))

    def _present(
        self, result: Admitted | Queued | Missed | Expired | Rejected
    ) -> QueueStatus:
        match result:
            case Admitted(seq=seq, expires_at_ms=exp, first_claim=first):
                if first:
                    EVENTS.labels("claimed").inc()
                return QueueStatus(
                    status="ALLOWED",
                    token=self._tokens.issue_access(seq, exp),
                    refresh_after_ms=self._refresh_after_ms,
                )
            case Queued(rank=rank):
                return self._waiting(rank)
            case Missed():
                EVENTS.labels("claim_missed").inc()
                return self._waiting(1)
            case Rejected(reason=reason):
                EVENTS.labels(reason.lower()).inc()
                return QueueStatus(status=reason)
            case Expired():
                return QueueStatus(status="EXPIRED")

    def _waiting(self, rank: int) -> QueueStatus:
        delay = min(30.0, max(1.0, rank / max(self._rate, 1) / 2))
        return QueueStatus(
            status="WAITING",
            rank=rank,
            retry_after_ms=int(delay * random.uniform(0.8, 1.2) * 1000),
        )
