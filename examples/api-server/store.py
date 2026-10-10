from contextlib import asynccontextmanager

import aiomysql

from settings import Settings

# 기존 Spring(JPA) 버전이 만든 테이블과 같은 구조라 ./data/mysql 데이터를 그대로 쓴다
SCHEMA = (
    """CREATE TABLE IF NOT EXISTS seats (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
        seat_no VARCHAR(255) NOT NULL,
        status ENUM('AVAILABLE', 'RESERVED', 'SOLD') NOT NULL
    )""",
    """CREATE TABLE IF NOT EXISTS reservation (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
        booker_name VARCHAR(100),
        reserved_date DATETIME(6),
        seat_id BIGINT,
        FOREIGN KEY (seat_id) REFERENCES seats (id)
    )""",
)
SEED_LOCK = "api-server-seat-init"


class ReservationError(ValueError):
    pass


class ConcertStore:
    def __init__(self, pool: aiomysql.Pool):
        self._pool = pool

    @classmethod
    async def connect(cls, settings: Settings) -> "ConcertStore":
        # autocommit: 조회가 트랜잭션을 열어 둔 채 풀에 돌아가면 다음 조회가 옛 스냅샷을 보게 된다
        pool = await aiomysql.create_pool(
            host=settings.mysql_host,
            port=settings.mysql_port,
            user=settings.mysql_user,
            password=settings.mysql_password,
            db=settings.mysql_database,
            maxsize=settings.pool_size,
            autocommit=True,
        )
        return cls(pool)

    async def close(self) -> None:
        self._pool.close()
        await self._pool.wait_closed()

    async def initialize(self, seat_count: int) -> None:
        async with self._pool.acquire() as conn, conn.cursor() as cur:
            for ddl in SCHEMA:
                await cur.execute(ddl)
            await cur.execute("SELECT GET_LOCK(%s, 60)", (SEED_LOCK,))
            if (await cur.fetchone())[0] != 1:
                raise RuntimeError("failed to acquire seat initialization lock")
            try:
                await cur.execute("SELECT COUNT(*) FROM seats")
                if (await cur.fetchone())[0] == 0:
                    await cur.executemany(
                        "INSERT INTO seats (seat_no, status) VALUES (%s, 'AVAILABLE')",
                        [(f"S-{n:04d}",) for n in range(1, seat_count + 1)],
                    )
            finally:
                await cur.execute("SELECT RELEASE_LOCK(%s)", (SEED_LOCK,))

    async def list_seats(self) -> list[dict]:
        async with self._pool.acquire() as conn, conn.cursor() as cur:
            await cur.execute("SELECT id, seat_no, status FROM seats ORDER BY id")
            return [
                {"id": id_, "seatNo": seat_no, "status": status}
                for id_, seat_no, status in await cur.fetchall()
            ]

    async def reserve(self, booker_name: str, seat_id: int | None) -> int:
        async with self._transaction() as cur:
            await cur.execute(
                "SELECT status FROM seats WHERE id = %s FOR UPDATE", (seat_id,)
            )
            row = await cur.fetchone()
            if row is None:
                raise ReservationError("There is no seat available for this seat")
            if row[0] != "AVAILABLE":
                raise ReservationError("This seat has already been reserved")
            await cur.execute(
                "UPDATE seats SET status = 'RESERVED' WHERE id = %s", (seat_id,)
            )
            await cur.execute(
                "INSERT INTO reservation (booker_name, reserved_date, seat_id) VALUES (%s, NOW(6), %s)",
                (booker_name, seat_id),
            )
            return cur.lastrowid

    async def reset(self) -> None:
        async with self._transaction() as cur:
            await cur.execute("SELECT id FROM seats FOR UPDATE")
            await cur.execute("DELETE FROM reservation")
            await cur.execute(
                "UPDATE seats SET status = 'AVAILABLE' WHERE status = 'RESERVED'"
            )

    @asynccontextmanager
    async def _transaction(self):
        async with self._pool.acquire() as conn:
            await conn.begin()
            try:
                async with conn.cursor() as cur:
                    yield cur
                await conn.commit()
            except BaseException:
                await conn.rollback()
                raise
