import time
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI, Request, Response
from fastapi.responses import PlainTextResponse
from prometheus_client import CONTENT_TYPE_LATEST, Histogram, generate_latest

from gateway import Gateway, SubjectRateLimiter, TokenVerifier, UpstreamProxy
from settings import Settings

# Spring Micrometer와 같은 이름·라벨이라 기존 Grafana 패널을 그대로 쓸 수 있다
REQUESTS = Histogram(
    "http_server_requests_seconds",
    "HTTP server requests",
    ["method", "uri", "status"],
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10),
)


async def record_metrics(request: Request, call_next):
    start = time.perf_counter()
    response = await call_next(request)
    route = request.scope.get("route")
    if route is not None and route.path not in ("/metrics", "/health"):
        REQUESTS.labels(request.method, route.path, str(response.status_code)).observe(
            time.perf_counter() - start
        )
    return response


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
    app.middleware("http")(record_metrics)

    @app.api_route(
        "/api/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE"]
    )
    async def api(request: Request) -> Response:
        return await request.app.state.gateway.handle(request)

    @app.get("/health", response_class=PlainTextResponse, include_in_schema=False)
    async def health():
        return "ok"

    @app.get("/metrics", include_in_schema=False)
    async def metrics():
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    return app
