import itertools
import time

import httpx
import jwt
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from fastapi import Request, Response
from fastapi.responses import JSONResponse

AUDIENCE = "turnstile:access"
ISSUER = "turnstile"
# 프록시가 그대로 넘기면 안 되는 헤더. content-encoding/length는 httpx가 본문을 풀어서 주므로 다시 계산되게 뺀다
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
    "host",
    "content-length",
    "content-encoding",
}


class TokenVerifier:
    def __init__(self, public_pem: bytes):
        self._key = load_pem_public_key(public_pem)

    def subject(self, authorization: str | None) -> str | None:
        if not authorization or not authorization.startswith("Bearer "):
            return None
        try:
            claims = jwt.decode(
                authorization.removeprefix("Bearer "),
                self._key,
                algorithms=["ES256"],
                audience=AUDIENCE,
                issuer=ISSUER,
            )
        except jwt.InvalidTokenError:
            return None
        return claims["sub"]


class SubjectRateLimiter:
    # ponytail: 인스턴스 메모리라 gateway를 N대로 늘리면 한도도 N배. 정확히 묶어야 하면 Redis로 옮길 것
    MAX_TRACKED = 100_000

    def __init__(self, rate_per_sec: float, burst: int, clock=time.monotonic):
        self._rate = rate_per_sec
        self._burst = burst
        self._clock = clock
        self._buckets: dict[str, tuple[float, float]] = {}

    def allow(self, sub: str) -> bool:
        now = self._clock()
        if len(self._buckets) > self.MAX_TRACKED:
            self._buckets = {k: v for k, v in self._buckets.items() if now - v[1] < 60}
        tokens, last = self._buckets.get(sub, (self._burst, now))
        tokens = min(self._burst, tokens + (now - last) * self._rate)
        allowed = tokens >= 1
        self._buckets[sub] = (tokens - 1 if allowed else tokens, now)
        return allowed


class UpstreamProxy:
    def __init__(self, upstreams: tuple[str, ...], client: httpx.AsyncClient):
        self._upstreams = itertools.cycle(upstreams)
        self._client = client

    async def forward(self, request: Request) -> Response:
        url = next(self._upstreams) + request.url.path
        if request.url.query:
            url += "?" + request.url.query
        headers = {
            k: v for k, v in request.headers.items() if k.lower() not in HOP_BY_HOP
        }
        try:
            upstream = await self._client.request(
                request.method, url, headers=headers, content=await request.body()
            )
        except httpx.HTTPError:
            return JSONResponse({"detail": "upstream unavailable"}, 502)
        return Response(
            upstream.content,
            upstream.status_code,
            headers={
                k: v for k, v in upstream.headers.items() if k.lower() not in HOP_BY_HOP
            },
        )


class Gateway:
    def __init__(
        self, verifier: TokenVerifier, limiter: SubjectRateLimiter, proxy: UpstreamProxy
    ):
        self._verifier = verifier
        self._limiter = limiter
        self._proxy = proxy

    async def handle(self, request: Request) -> Response:
        sub = self._verifier.subject(request.headers.get("authorization"))
        if sub is None:
            return JSONResponse(
                {"detail": "queue token required"},
                401,
                headers={"WWW-Authenticate": "Bearer"},
            )
        if not self._limiter.allow(sub):
            return JSONResponse(
                {"detail": "too many requests"}, 429, headers={"Retry-After": "1"}
            )
        return await self._proxy.forward(request)
