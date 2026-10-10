import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    upstreams: tuple[str, ...] = ("http://app1:8080", "http://app2:8080")
    public_key_file: str = "/keys/public.pem"
    rate_per_sec: float = 20
    burst: int = 40
    timeout: float = 10.0

    @classmethod
    def from_env(cls) -> "Settings":
        env = os.environ.get
        upstreams = env("UPSTREAMS")
        return cls(
            upstreams=(
                tuple(u.strip() for u in upstreams.split(","))
                if upstreams
                else cls.upstreams
            ),
            public_key_file=env("QUEUE_PUBLIC_KEY_FILE", cls.public_key_file),
            rate_per_sec=float(env("TOKEN_RATE_PER_SEC", cls.rate_per_sec)),
            burst=int(env("TOKEN_BURST", cls.burst)),
            timeout=float(env("UPSTREAM_TIMEOUT_SECONDS", cls.timeout)),
        )
