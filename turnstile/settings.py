import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    private_key_file: str = "/run/secrets/turnstile_key"
    redis_url: str = "redis://redis:6379"
    # 아래 셋은 Redis config hash가 비어 있을 때의 기본값. 운영 중에는 PUT /admin/config로 바꾼다
    max_active: int = 100
    admit_rate: int = 100
    max_waiting: int = 1_000_000
    session_ms: int = 60_000
    max_session_ms: int = 1_800_000
    claim_ms: int = 10_000
    ticket_ttl: int = 86_400  # 초
    dispatch_interval: float = 0.1  # 초
    admin_token: str | None = None
    cookie_secure: bool = True

    @classmethod
    def from_env(cls) -> "Settings":
        env = os.environ.get
        return cls(
            private_key_file=env("PRIVATE_KEY_FILE", cls.private_key_file),
            redis_url=env("REDIS_URL", cls.redis_url),
            max_active=int(env("MAX_ACTIVE_USERS", cls.max_active)),
            admit_rate=int(env("ADMIT_RATE_PER_SEC", cls.admit_rate)),
            max_waiting=int(env("MAX_WAITING", cls.max_waiting)),
            session_ms=int(env("SESSION_TTL_SECONDS", 60)) * 1000,
            max_session_ms=int(env("MAX_SESSION_SECONDS", 1800)) * 1000,
            claim_ms=int(env("CLAIM_TIMEOUT_SECONDS", 10)) * 1000,
            ticket_ttl=int(env("TICKET_TTL_SECONDS", cls.ticket_ttl)),
            dispatch_interval=int(env("DISPATCH_INTERVAL_MS", 100)) / 1000,
            admin_token=env("ADMIN_TOKEN") or None,
            cookie_secure=env("COOKIE_SECURE", "true") == "true",
        )
