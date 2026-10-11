from dataclasses import dataclass

import redis.asyncio as redis

from settings import Settings

KEEP_MS = 300_000

# ponytail: 큐 하나 = Redis primary 하나. 수십만 ops/s를 넘으면 공연별 키 prefix + Cluster hash tag로 샤딩
K = {
    name: f"turnstile:{name}"
    for name in (
        "seq",
        "waiting",
        "pending",
        "active",
        "bucket",
        "cursor",
        "config",
        "deadline",
    )
}
KEYS = list(K.values())  # Lua의 KEYS[1..8] 순서

# 모든 시각은 Redis TIME 기준이라 인스턴스 간 시계 차이의 영향을 받지 않는다.
# member는 seq. waiting(score=seq) → pending(claim 기한) → active(세션 만료), deadline(최대 세션 기한)
PRELUDE = """
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local c = redis.call('HMGET', KEYS[7], 'max_active', 'admit_rate', 'max_waiting', 'state')
local max_active, rate, max_waiting = tonumber(c[1] or ARGV[1]), tonumber(c[2] or ARGV[2]), tonumber(c[3] or ARGV[3])
local state = c[4] or 'open'
local session, claim, keep, max_session = tonumber(ARGV[4]), tonumber(ARGV[5]), tonumber(ARGV[6]), tonumber(ARGV[7])
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - keep)
redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now - keep)
redis.call('ZREMRANGEBYSCORE', KEYS[8], '-inf', now - keep)
local function admit(seq)
    local cap = now + max_session
    redis.call('ZADD', KEYS[8], cap, seq)
    local exp = math.min(now + session, cap)
    redis.call('ZADD', KEYS[4], exp, seq)
    return exp
end
local used = redis.call('ZCOUNT', KEYS[3], '(' .. now, '+inf') + redis.call('ZCOUNT', KEYS[4], '(' .. now, '+inf')
local function take(n)
    local b = redis.call('HMGET', KEYS[5], 'tokens', 'ts')
    local tokens = math.min(rate, (tonumber(b[1]) or rate) + (now - (tonumber(b[2]) or now)) * rate / 1000)
    local k = math.max(0, math.min(n, math.floor(tokens)))
    redis.call('HSET', KEYS[5], 'tokens', tokens - k, 'ts', now)
    return k
end
local function cursor() return tonumber(redis.call('GET', KEYS[6]) or 0) end
"""

ENTER = PRELUDE + """
if state == 'closed' then return {'CLOSED'} end
local waiting = redis.call('ZCARD', KEYS[2])
if waiting >= max_waiting then return {'FULL'} end
local seq = redis.call('INCR', KEYS[1])
if state == 'open' and waiting == 0 and used < max_active and take(1) == 1 then
    redis.call('SET', KEYS[6], seq)
    return {'ALLOWED', seq, admit(seq)}
end
redis.call('ZADD', KEYS[2], seq, seq)
return {'WAITING', seq, seq - cursor()}
"""

# ponytail: 이탈자도 pop되며 입장 rate를 소모함. 이탈률이 높으면 last-seen heartbeat로 걸러낼 것
DISPATCH = PRELUDE + """
local waiting = redis.call('ZCARD', KEYS[2])
local admitted = 0
if state == 'open' then
    local n = take(math.min(max_active - used, waiting))
    if n > 0 then
        local popped = redis.call('ZPOPMIN', KEYS[2], n)
        for i = 1, #popped, 2 do redis.call('ZADD', KEYS[3], now + claim, popped[i]) end
        admitted = #popped / 2
        if tonumber(popped[#popped]) > cursor() then redis.call('SET', KEYS[6], popped[#popped]) end
    end
end
return {admitted, waiting - admitted, used + admitted, cursor(), state, rate}
"""

CLAIM = PRELUDE + """
local seq = ARGV[8]
local exp = tonumber(redis.call('ZSCORE', KEYS[4], seq))
if exp then
    if exp > now then return {'ALLOWED', exp} end
    return {'EXPIRED'}
end
local due = tonumber(redis.call('ZSCORE', KEYS[3], seq))
if due then
    redis.call('ZREM', KEYS[3], seq)
    if due > now then return {'CLAIMED', admit(seq)} end
    redis.call('ZADD', KEYS[2], seq, seq)
    return {'MISSED'}
end
local rank = redis.call('ZRANK', KEYS[2], seq)
if rank then return {'WAITING', rank + 1} end
return {'EXPIRED'}
"""

REFRESH = PRELUDE + """
local seq = ARGV[8]
local exp = tonumber(redis.call('ZSCORE', KEYS[4], seq))
if not exp or exp <= now then return {'EXPIRED'} end
local extended = math.min(now + session, tonumber(redis.call('ZSCORE', KEYS[8], seq)) or exp)
if extended > exp then
    redis.call('ZADD', KEYS[4], extended, seq)
    exp = extended
end
return {'ALLOWED', exp}
"""


@dataclass(frozen=True)
class Admitted:
    seq: int
    expires_at_ms: int


@dataclass(frozen=True)
class Queued:
    seq: int
    rank: int


@dataclass(frozen=True)
class Missed:
    seq: int


@dataclass(frozen=True)
class Expired:
    seq: int


@dataclass(frozen=True)
class Rejected:
    reason: str


@dataclass(frozen=True)
class DispatchResult:
    admitted: int
    waiting: int
    used: int
    cursor: int
    state: str
    rate: int


class QueueStore:
    def __init__(self, client: redis.Redis, dispatcher: redis.Redis, settings: Settings):
        self._redis = client
        self._dispatcher = dispatcher
        self._args = [
            settings.max_active,
            settings.admit_rate,
            settings.max_waiting,
            settings.session_ms,
            settings.claim_ms,
            KEEP_MS,
            settings.max_session_ms,
        ]
        self._enter = client.register_script(ENTER)
        self._dispatch = dispatcher.register_script(DISPATCH)
        self._claim = client.register_script(CLAIM)
        self._refresh = client.register_script(REFRESH)

    @classmethod
    def connect(cls, settings: Settings) -> "QueueStore":
        # 기본 풀은 한도를 넘으면 바로 에러를 낸다. Blocking 풀은 연결 수를 묶어 두고, 초과 요청은 최대 1초 기다리게 한다
        pool = redis.BlockingConnectionPool.from_url(
            settings.redis_url,
            max_connections=50,
            timeout=1,
            decode_responses=True,
            socket_timeout=1,
            socket_connect_timeout=1,
        )
        # 디스패처 전용 연결. 요청과 같은 풀에서 줄을 서면 과부하 때 연결을 못 받아 입장이 멈춘다
        dispatcher = redis.Redis.from_url(
            settings.redis_url,
            max_connections=1,
            decode_responses=True,
            socket_timeout=1,
            socket_connect_timeout=1,
        )
        return cls(redis.Redis(connection_pool=pool), dispatcher, settings)

    async def close(self) -> None:
        await self._redis.connection_pool.disconnect()
        await self._dispatcher.connection_pool.disconnect()

    async def enter(self) -> Admitted | Queued | Rejected:
        match await self._enter(keys=KEYS, args=self._args):
            case ["ALLOWED", seq, exp]:
                return Admitted(seq, exp)
            case ["WAITING", seq, rank]:
                return Queued(seq, rank)
            case [reason]:
                return Rejected(reason)

    async def dispatch(self) -> DispatchResult:
        return DispatchResult(*await self._dispatch(keys=KEYS, args=self._args))

    async def claim(self, seq: int) -> Admitted | Queued | Missed | Expired:
        match await self._claim(keys=KEYS, args=[*self._args, seq]):
            case ["CLAIMED" | "ALLOWED", exp]:
                return Admitted(seq, exp)
            case ["MISSED"]:
                return Missed(seq)
            case ["WAITING", rank]:
                return Queued(seq, rank)
            case _:
                return Expired(seq)

    async def refresh(self, seq: int) -> Admitted | Expired:
        match await self._refresh(keys=KEYS, args=[*self._args, seq]):
            case ["ALLOWED", exp]:
                return Admitted(seq, exp)
            case _:
                return Expired(seq)

    async def release(self, seq: str) -> None:
        await self._redis.zrem(K["active"], seq)

    async def update_config(self, values: dict) -> dict:
        await self._redis.hset(K["config"], mapping=values)
        return await self._redis.hgetall(K["config"])
