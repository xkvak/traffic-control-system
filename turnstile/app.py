import asyncio
import hmac
import logging
import random
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Literal

import jwt
from fastapi import (
    APIRouter,
    Cookie,
    Depends,
    FastAPI,
    HTTPException,
    Request,
    Response,
)
from fastapi.responses import JSONResponse, PlainTextResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, ConfigDict, Field, model_validator
from redis.exceptions import RedisError

from service import InvalidTicket, QueueStatus, Turnstile
from settings import Settings
from store import QueueStore
from tokens import TokenIssuer

COOKIE = "turnstile_ticket"
log = logging.getLogger("turnstile")


class ConfigUpdate(BaseModel):
    """PUT /admin/config 본문. 넣은 필드만 바뀌고, 모든 인스턴스가 다음 dispatch tick에 반영한다."""

    model_config = ConfigDict(extra="forbid", strict=True)

    max_active: int | None = Field(None, ge=0)
    admit_rate: int | None = Field(None, ge=0)
    max_waiting: int | None = Field(None, ge=0)
    state: Literal["open", "paused", "closed"] | None = None

    @model_validator(mode="after")
    def _not_empty(self):
        if not self.model_dump(exclude_none=True):
            raise ValueError("set at least one field")
        return self


bearer = HTTPBearer(auto_error=False)
Credentials = Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]
TicketCookie = Annotated[str | None, Cookie(alias=COOKIE)]


async def get_turnstile(request: Request) -> Turnstile:
    return request.app.state.turnstile


async def get_settings(request: Request) -> Settings:
    return request.app.state.settings


TurnstileDep = Annotated[Turnstile, Depends(get_turnstile)]
SettingsDep = Annotated[Settings, Depends(get_settings)]


async def no_store(response: Response) -> None:
    response.headers["Cache-Control"] = "no-store"


async def require_admin(settings: SettingsDep, credentials: Credentials) -> None:
    if not settings.admin_token:
        raise HTTPException(404)
    if credentials is None or not hmac.compare_digest(
        credentials.credentials.encode(), settings.admin_token.encode()
    ):
        raise HTTPException(401, "unauthorized")


queue = APIRouter(prefix="/queue", tags=["queue"], dependencies=[Depends(no_store)])
admin = APIRouter(
    prefix="/admin",
    tags=["admin"],
    dependencies=[Depends(no_store), Depends(require_admin)],
)


@queue.post("/enter", response_model=QueueStatus, response_model_exclude_none=True)
async def enter(
    turnstile: TurnstileDep,
    settings: SettingsDep,
    response: Response,
    ticket: TicketCookie = None,
):
    """진입. 자리가 있으면 바로 ALLOWED, 아니면 WAITING과 ticket 쿠키를 받는다."""
    status, new_ticket = await turnstile.enter(ticket)
    if new_ticket:
        response.set_cookie(
            COOKIE,
            new_ticket,
            max_age=settings.ticket_ttl,
            path="/queue",
            httponly=True,
            secure=settings.cookie_secure,
            samesite="lax",
        )
    return status


@queue.get("/status", response_model=QueueStatus, response_model_exclude_none=True)
async def status(turnstile: TurnstileDep, ticket: TicketCookie = None):
    """폴링. retryAfterMs 뒤에 다시 호출한다."""
    return await turnstile.poll(ticket)


@queue.post("/refresh", response_model=QueueStatus, response_model_exclude_none=True)
async def refresh(turnstile: TurnstileDep, credentials: Credentials):
    """입장한 사용자가 refreshAfterMs마다 호출해 세션을 연장한다. 응답의 새 token으로 교체해서 쓴다."""
    try:
        return await turnstile.refresh(credentials.credentials if credentials else "")
    except jwt.InvalidTokenError:
        raise HTTPException(401, "invalid token")


@queue.post("/leave", status_code=204)
async def leave(turnstile: TurnstileDep, credentials: Credentials):
    """예매를 마친 사용자가 access token으로 슬롯을 바로 반납한다."""
    try:
        await turnstile.leave(credentials.credentials if credentials else "")
    except jwt.InvalidTokenError:
        raise HTTPException(401, "invalid token")
    return Response(status_code=204)


@admin.put("/config")
async def update_config(body: ConfigUpdate, turnstile: TurnstileDep) -> dict[str, str]:
    return await turnstile.configure(body.model_dump(exclude_none=True))


async def redis_unavailable(request: Request, exc: RedisError):
    log.warning("redis unavailable: %s", exc)
    headers = {"Cache-Control": "no-store", "Retry-After": str(random.randint(1, 3))}
    return JSONResponse({"detail": "unavailable"}, 503, headers=headers)


async def invalid_ticket(request: Request, exc: InvalidTicket):
    return JSONResponse(
        {"detail": "invalid ticket"}, 400, headers={"Cache-Control": "no-store"}
    )


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    tokens = TokenIssuer(
        Path(settings.private_key_file).read_bytes(), settings.ticket_ttl
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        store = QueueStore.connect(settings)
        app.state.turnstile = Turnstile(store, tokens, settings.session_ms)
        dispatcher = asyncio.create_task(
            app.state.turnstile.run_dispatcher(settings.dispatch_interval)
        )
        yield
        dispatcher.cancel()
        await store.close()

    app = FastAPI(title="Turnstile", lifespan=lifespan)
    app.state.settings = settings
    app.include_router(queue)
    app.include_router(admin)
    app.add_exception_handler(RedisError, redis_unavailable)
    app.add_exception_handler(InvalidTicket, invalid_ticket)

    @app.get("/health", response_class=PlainTextResponse, include_in_schema=False)
    async def health():
        return "ok"

    @app.get("/metrics", include_in_schema=False)
    async def metrics():
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    return app
