FROM python:3.13-slim
COPY --from=ghcr.io/astral-sh/uv:0.12.21 /uv /bin/uv

WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY *.py ./

EXPOSE 8080
# 컨테이너당 프로세스 1개, 확장은 replica로 한다
CMD ["/app/.venv/bin/uvicorn", "app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
