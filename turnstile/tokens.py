import hashlib
import time

import jwt
from cryptography.hazmat.primitives.serialization import load_pem_private_key

AUDIENCE = "turnstile:access"


class TokenIssuer:
    def __init__(self, private_pem: bytes, ticket_ttl: int):
        self._private_key = load_pem_private_key(private_pem, None)
        self._public_key = self._private_key.public_key()
        # ticket 전용 HMAC 키. 공개키로는 검증되지 않으므로 ticket을 access token으로 쓸 수 없다
        self._ticket_key = hashlib.sha256(private_pem).digest()
        self._ticket_ttl = ticket_ttl

    def issue_ticket(self, seq: int) -> str:
        claims = {"sub": str(seq), "exp": int(time.time()) + self._ticket_ttl}
        return jwt.encode(claims, self._ticket_key, algorithm="HS256")

    def read_ticket(self, raw: str | None) -> int | None:
        try:
            return int(
                jwt.decode(raw or "", self._ticket_key, algorithms=["HS256"])["sub"]
            )
        except (jwt.InvalidTokenError, KeyError, ValueError):
            return None

    def issue_access(self, seq: int, expires_at_ms: int) -> str:
        claims = {
            "iss": "turnstile",
            "aud": AUDIENCE,
            "sub": str(seq),
            "exp": expires_at_ms // 1000,
        }
        return jwt.encode(claims, self._private_key, algorithm="ES256")

    def verify_access(self, token: str) -> dict:
        return jwt.decode(
            token, self._public_key, algorithms=["ES256"], audience=AUDIENCE
        )
