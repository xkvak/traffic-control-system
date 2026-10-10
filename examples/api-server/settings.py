import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    mysql_host: str = "mysql"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = "root"
    mysql_database: str = "concert_db"
    pool_size: int = 20
    seat_count: int = 1_000

    @classmethod
    def from_env(cls) -> "Settings":
        env = os.environ.get
        return cls(
            mysql_host=env("MYSQL_HOST", cls.mysql_host),
            mysql_port=int(env("MYSQL_PORT", cls.mysql_port)),
            mysql_user=env("MYSQL_USER", cls.mysql_user),
            mysql_password=env("MYSQL_PASSWORD", cls.mysql_password),
            mysql_database=env("MYSQL_DATABASE", cls.mysql_database),
            pool_size=int(env("MYSQL_POOL_SIZE", cls.pool_size)),
        )
