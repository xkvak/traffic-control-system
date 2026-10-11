from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import APIRouter, Depends, FastAPI, Request, Response
from fastapi.responses import JSONResponse, PlainTextResponse
from pydantic import BaseModel

from settings import Settings
from store import ConcertStore, ReservationError

MAX_BOOKER_NAME_LENGTH = 100


class ReservationRequest(BaseModel):
    bookerName: str | None = None
    seatId: int | None = None


async def get_store(request: Request) -> ConcertStore:
    return request.app.state.store


StoreDep = Annotated[ConcertStore, Depends(get_store)]
router = APIRouter(prefix="/api/v1")


@router.get("/concerts/seats")
async def list_seats(store: StoreDep):
    return JSONResponse(await store.list_seats())


@router.delete("/concerts/seats", status_code=204)
async def reset_seats(store: StoreDep):
    await store.reset()
    return Response(status_code=204)


@router.post("/reservation", response_class=PlainTextResponse)
async def reserve(body: ReservationRequest, store: StoreDep):
    booker_name = (body.bookerName or "").strip()
    if not booker_name:
        raise ReservationError("예매자 이름을 입력해 주세요.")
    if len(booker_name) > MAX_BOOKER_NAME_LENGTH:
        raise ReservationError("예매자 이름은 100자 이하로 입력해 주세요.")
    return str(await store.reserve(booker_name, body.seatId))


async def reservation_error(request: Request, exc: ReservationError):
    return PlainTextResponse(str(exc), 400)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        store = await ConcertStore.connect(settings)
        await store.initialize(settings.seat_count)
        app.state.store = store
        yield
        await store.close()

    app = FastAPI(title="Concert API", lifespan=lifespan)
    app.include_router(router)
    app.add_exception_handler(ReservationError, reservation_error)

    @app.get("/health", response_class=PlainTextResponse, include_in_schema=False)
    async def health():
        return "ok"

    return app
