from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI, Request, Response
from fastapi.responses import PlainTextResponse

from gateway import Gateway, SubjectRateLimiter, TokenVerifier, UpstreamProxy
from settings import Settings

def create_app(
    settings: Settings | None = None, transport: httpx.AsyncBaseTransport | None = None
) -> FastAPI:
    settings = settings or Settings.from_env()
    verifier = TokenVerifier(Path(settings.public_key_file).read_bytes())

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        async with httpx.AsyncClient(
            transport=transport, timeout=settings.timeout
        ) as client:
            app.state.gateway = Gateway(
                verifier,
                SubjectRateLimiter(settings.rate_per_sec, settings.burst),
                UpstreamProxy(settings.upstreams, client),
            )
            yield

    app = FastAPI(title="API Gateway", lifespan=lifespan)

    @app.api_route(
        "/api/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE"]
    )
    async def api(request: Request) -> Response:
        return await request.app.state.gateway.handle(request)

    @app.get("/health", response_class=PlainTextResponse, include_in_schema=False)
    async def health():
        return "ok"

    return app
