#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import json
import math
import statistics
import time
from collections import Counter
from dataclasses import dataclass, field
from typing import Iterable
from urllib.parse import parse_qs, urljoin, urlsplit


DEFAULT_GATEWAY_ORIGIN = "http://localhost:8080"
DEFAULT_WEB_ORIGIN = "http://localhost:5173"
DEFAULT_API_PATH = "/api/v1/concerts/seats"
DEFAULT_PAGE_URI = "/?loadTest=1"
DEFAULT_QUEUE_ENTRY_MODE = "gateway-sse"


@dataclass
class HttpResponse:
    url: str
    status: int
    reason: str
    headers: dict[str, str]
    body: bytes = b""


@dataclass
class StreamResponse:
    url: str
    status: int
    reason: str
    headers: dict[str, str]
    reader: asyncio.StreamReader
    writer: asyncio.StreamWriter

    async def close(self) -> None:
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except Exception:
            pass


@dataclass
class FlowResult:
    outcome: str
    initial_status: int | None = None
    final_status: int | None = None
    initial_latency_ms: float | None = None
    total_latency_ms: float | None = None
    queue_wait_ms: float | None = None
    queue_redirect_url: str | None = None
    error: str | None = None


@dataclass
class StageResult:
    name: str
    target_rps: int
    duration_seconds: float
    launched: int = 0
    started_at: float = 0.0
    finished_at: float = 0.0
    counters: Counter = field(default_factory=Counter)
    initial_latencies_ms: list[float] = field(default_factory=list)
    total_latencies_ms: list[float] = field(default_factory=list)
    queue_wait_latencies_ms: list[float] = field(default_factory=list)
    errors: Counter = field(default_factory=Counter)

    def record(self, result: FlowResult) -> None:
        self.counters[result.outcome] += 1
        if result.error:
            self.errors[result.error] += 1
        if result.initial_latency_ms is not None:
            self.initial_latencies_ms.append(result.initial_latency_ms)
        if result.total_latency_ms is not None:
            self.total_latencies_ms.append(result.total_latency_ms)
        if result.queue_wait_ms is not None:
            self.queue_wait_latencies_ms.append(result.queue_wait_ms)

    def as_dict(self) -> dict:
        elapsed = max(self.finished_at - self.started_at, 0.0)
        return {
            "name": self.name,
            "target_rps": self.target_rps,
            "duration_seconds": self.duration_seconds,
            "elapsed_seconds": elapsed,
            "launched": self.launched,
            "launch_rps": round(self.launched / self.duration_seconds, 2) if self.duration_seconds else 0.0,
            "completed_rps": round(self.launched / elapsed, 2) if elapsed else 0.0,
            "counters": dict(self.counters),
            "initial_latency_ms": summarize_latencies(self.initial_latencies_ms),
            "total_latency_ms": summarize_latencies(self.total_latencies_ms),
            "queue_wait_latency_ms": summarize_latencies(self.queue_wait_latencies_ms),
            "errors": dict(self.errors),
        }


@dataclass(frozen=True)
class StageConfig:
    name: str
    rps: int
    duration_seconds: float


@dataclass(frozen=True)
class QueueEntry:
    request_id: str
    requested_uri: str | None
    queue_page_path: str | None
    queue_sse_url: str


@dataclass(frozen=True)
class LoadConfig:
    gateway_origin: str
    web_origin: str
    queue_origin: str
    api_path: str
    current_page_uri: str
    queue_timeout_seconds: float
    request_timeout_seconds: float
    queue_entry_mode: str
    verbose: bool

    @property
    def api_url(self) -> str:
        return urljoin(self.gateway_origin, self.api_path)


def summarize_latencies(values: Iterable[float]) -> dict[str, float] | None:
    samples = sorted(values)
    if not samples:
        return None

    def percentile(p: float) -> float:
        if len(samples) == 1:
            return round(samples[0], 2)
        rank = (len(samples) - 1) * p
        lower = math.floor(rank)
        upper = math.ceil(rank)
        if lower == upper:
            return round(samples[lower], 2)
        weight = rank - lower
        value = samples[lower] + (samples[upper] - samples[lower]) * weight
        return round(value, 2)

    return {
        "count": len(samples),
        "min": round(samples[0], 2),
        "p50": percentile(0.50),
        "p90": percentile(0.90),
        "p95": percentile(0.95),
        "p99": percentile(0.99),
        "max": round(samples[-1], 2),
        "avg": round(statistics.fmean(samples), 2),
    }


def parse_stages(values: list[str]) -> list[StageConfig]:
    stages: list[StageConfig] = []
    for index, raw in enumerate(values, start=1):
        try:
            rps_text, duration_text = raw.lower().split("x", 1)
            rps = int(rps_text)
            duration_seconds = float(duration_text)
        except ValueError as exc:
            raise argparse.ArgumentTypeError(
                f"Invalid stage '{raw}'. Expected format like 500x10 or 5000x2."
            ) from exc

        if rps <= 0 or duration_seconds <= 0:
            raise argparse.ArgumentTypeError(f"Invalid stage '{raw}'. RPS and duration must be positive.")

        stages.append(StageConfig(name=f"stage-{index}", rps=rps, duration_seconds=duration_seconds))
    return stages


def normalize_origin(value: str) -> str:
    return value.rstrip("/")


def remaining_seconds(deadline: float) -> float:
    remaining = deadline - time.perf_counter()
    if remaining <= 0:
        raise asyncio.TimeoutError("deadline exceeded")
    return remaining


def host_header_for(parts) -> str:
    default_port = 80
    port = parts.port or default_port
    if port == default_port:
        return parts.hostname or "localhost"
    return f"{parts.hostname}:{port}"


async def open_http_connection(url: str, timeout_seconds: float) -> tuple[asyncio.StreamReader, asyncio.StreamWriter, str]:
    parts = urlsplit(url)
    if parts.scheme != "http":
        raise ValueError(f"Only plain HTTP is supported: {url}")
    host = parts.hostname or "localhost"
    port = parts.port or 80
    reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=timeout_seconds)
    path = parts.path or "/"
    if parts.query:
        path = f"{path}?{parts.query}"
    return reader, writer, path


async def read_response_headers(reader: asyncio.StreamReader, deadline: float) -> tuple[int, str, dict[str, str]]:
    status_line_timeout = remaining_seconds(deadline)
    status_line = await asyncio.wait_for(reader.readline(), timeout=status_line_timeout)
    if not status_line:
        raise ConnectionError("empty status line")

    try:
        _, status_text, reason = status_line.decode("iso-8859-1").rstrip("\r\n").split(" ", 2)
    except ValueError:
        _, status_text = status_line.decode("iso-8859-1").rstrip("\r\n").split(" ", 1)
        reason = ""

    headers: dict[str, str] = {}
    while True:
        line_timeout = remaining_seconds(deadline)
        line = await asyncio.wait_for(reader.readline(), timeout=line_timeout)
        if line in (b"\r\n", b"\n", b""):
            break
        key, value = line.decode("iso-8859-1").split(":", 1)
        headers[key.strip().lower()] = value.strip()

    return int(status_text), reason, headers


async def iter_chunked_body(reader: asyncio.StreamReader, deadline: float):
    while True:
        size_line_timeout = remaining_seconds(deadline)
        size_line = await asyncio.wait_for(reader.readline(), timeout=size_line_timeout)
        if not size_line:
            raise ConnectionError("unexpected EOF while reading chunk size")

        chunk_size = int(size_line.split(b";", 1)[0].strip(), 16)
        if chunk_size == 0:
            end_marker_timeout = remaining_seconds(deadline)
            await asyncio.wait_for(reader.readline(), timeout=end_marker_timeout)
            while True:
                trailer_timeout = remaining_seconds(deadline)
                trailer = await asyncio.wait_for(reader.readline(), timeout=trailer_timeout)
                if trailer in (b"\r\n", b"\n", b""):
                    break
            return

        chunk_timeout = remaining_seconds(deadline)
        chunk = await asyncio.wait_for(reader.readexactly(chunk_size), timeout=chunk_timeout)
        chunk_suffix_timeout = remaining_seconds(deadline)
        await asyncio.wait_for(reader.readexactly(2), timeout=chunk_suffix_timeout)
        yield chunk


async def read_full_body(reader: asyncio.StreamReader, headers: dict[str, str], deadline: float) -> bytes:
    transfer_encoding = headers.get("transfer-encoding", "").lower()
    if "chunked" in transfer_encoding:
        parts: list[bytes] = []
        async for chunk in iter_chunked_body(reader, deadline):
            parts.append(chunk)
        return b"".join(parts)

    content_length = headers.get("content-length")
    if content_length is not None:
        length = int(content_length)
        if length == 0:
            return b""
        body_timeout = remaining_seconds(deadline)
        return await asyncio.wait_for(reader.readexactly(length), timeout=body_timeout)

    read_timeout = remaining_seconds(deadline)
    return await asyncio.wait_for(reader.read(), timeout=read_timeout)


async def http_request(
    method: str,
    url: str,
    *,
    headers: dict[str, str] | None = None,
    timeout_seconds: float,
    stream: bool = False,
) -> HttpResponse | StreamResponse:
    request_headers = {
        "Host": host_header_for(urlsplit(url)),
        "Connection": "close",
        "User-Agent": "gateway-queue-load-test/1.0",
        **(headers or {}),
    }

    reader, writer, path = await open_http_connection(url, timeout_seconds=timeout_seconds)
    deadline = time.perf_counter() + timeout_seconds

    header_lines = "".join(f"{key}: {value}\r\n" for key, value in request_headers.items())
    request_bytes = f"{method} {path} HTTP/1.1\r\n{header_lines}\r\n".encode("utf-8")
    writer.write(request_bytes)
    await asyncio.wait_for(writer.drain(), timeout=remaining_seconds(deadline))

    status, reason, response_headers = await read_response_headers(reader, deadline)
    if stream:
        return StreamResponse(url=url, status=status, reason=reason, headers=response_headers, reader=reader, writer=writer)

    try:
        body = await read_full_body(reader, response_headers, deadline)
    finally:
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass

    return HttpResponse(url=url, status=status, reason=reason, headers=response_headers, body=body)


class SseParser:
    def __init__(self) -> None:
        self._buffer = ""

    def feed(self, chunk: bytes) -> list[tuple[str, str]]:
        self._buffer += chunk.decode("utf-8", errors="replace")
        events: list[tuple[str, str]] = []

        while True:
            separator_index = self._buffer.find("\n\n")
            separator_length = 2
            if separator_index < 0:
                separator_index = self._buffer.find("\r\n\r\n")
                separator_length = 4
            if separator_index < 0:
                break

            block = self._buffer[:separator_index]
            self._buffer = self._buffer[separator_index + separator_length :]
            event_name = "message"
            data_lines: list[str] = []

            for raw_line in block.replace("\r\n", "\n").split("\n"):
                if not raw_line or raw_line.startswith(":"):
                    continue
                if raw_line.startswith("event:"):
                    event_name = raw_line[6:].strip()
                    continue
                if raw_line.startswith("data:"):
                    data_lines.append(raw_line[5:].strip())

            events.append((event_name, "\n".join(data_lines)))

        return events


async def read_sse_event(stream: StreamResponse, timeout_seconds: float) -> tuple[str, dict | None]:
    deadline = time.perf_counter() + timeout_seconds
    parser = SseParser()
    transfer_encoding = stream.headers.get("transfer-encoding", "").lower()

    try:
        if "chunked" in transfer_encoding:
            async for chunk in iter_chunked_body(stream.reader, deadline):
                for event_name, raw_data in parser.feed(chunk):
                    payload = json.loads(raw_data) if raw_data else None
                    if event_name in {"allowed", "expired"}:
                        return event_name, payload
        else:
            while True:
                chunk = await asyncio.wait_for(stream.reader.read(4096), timeout=remaining_seconds(deadline))
                if not chunk:
                    raise ConnectionError("SSE stream ended before ALLOWED or EXPIRED event")
                for event_name, raw_data in parser.feed(chunk):
                    payload = json.loads(raw_data) if raw_data else None
                    if event_name in {"allowed", "expired"}:
                        return event_name, payload
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid SSE payload: {exc}") from exc

    raise asyncio.TimeoutError("queue wait timed out")


async def await_queue_grant(queue_sse_url: str, timeout_seconds: float) -> tuple[str, dict | None]:
    stream = await http_request(
        "GET",
        queue_sse_url,
        headers={"Accept": "text/event-stream"},
        timeout_seconds=timeout_seconds,
        stream=True,
    )
    assert isinstance(stream, StreamResponse)

    if stream.status != 200:
        await stream.close()
        raise RuntimeError(f"queue SSE returned HTTP {stream.status}")

    try:
        return await read_sse_event(stream, timeout_seconds)
    finally:
        await stream.close()


async def execute_flow(sequence: int, config: LoadConfig) -> FlowResult:
    start = time.perf_counter()
    headers = {
        "Accept": "application/json",
        "X-Current-Page-Uri": config.current_page_uri,
    }

    try:
        initial = await http_request("GET", config.api_url, headers=headers, timeout_seconds=config.request_timeout_seconds)
        assert isinstance(initial, HttpResponse)
        initial_latency_ms = (time.perf_counter() - start) * 1000.0

        if initial.status == 200:
            return FlowResult(
                outcome="direct_success",
                initial_status=initial.status,
                final_status=initial.status,
                initial_latency_ms=initial_latency_ms,
                total_latency_ms=(time.perf_counter() - start) * 1000.0,
            )

        if initial.status not in (202, 302, 303, 307, 308):
            return FlowResult(
                outcome="initial_non_redirect_failure",
                initial_status=initial.status,
                final_status=initial.status,
                initial_latency_ms=initial_latency_ms,
                total_latency_ms=(time.perf_counter() - start) * 1000.0,
                error=f"initial_http_{initial.status}",
            )

        queue_entry = resolve_queue_entry(initial, config)
        if not queue_entry:
            return FlowResult(
                outcome="queue_page_failure",
                initial_status=initial.status,
                final_status=initial.status,
                initial_latency_ms=initial_latency_ms,
                total_latency_ms=(time.perf_counter() - start) * 1000.0,
                error="missing_queue_entry",
            )

        if config.queue_entry_mode == "web-client":
            if not queue_entry.queue_page_path:
                return FlowResult(
                    outcome="queue_page_failure",
                    initial_status=initial.status,
                    final_status=initial.status,
                    initial_latency_ms=initial_latency_ms,
                    total_latency_ms=(time.perf_counter() - start) * 1000.0,
                    queue_redirect_url=queue_entry.queue_sse_url,
                    error="missing_queue_page_path",
                )

            queue_page = await http_request(
                "GET",
                urljoin(config.web_origin, queue_entry.queue_page_path),
                headers={"Accept": "text/html"},
                timeout_seconds=config.request_timeout_seconds,
            )
            assert isinstance(queue_page, HttpResponse)

            if queue_page.status != 200:
                return FlowResult(
                    outcome="queue_page_failure",
                    initial_status=initial.status,
                    final_status=queue_page.status,
                    initial_latency_ms=initial_latency_ms,
                    total_latency_ms=(time.perf_counter() - start) * 1000.0,
                    queue_redirect_url=queue_entry.queue_page_path,
                    error=f"queue_page_http_{queue_page.status}",
                )

        queue_wait_started_at = time.perf_counter()
        event_name, payload = await await_queue_grant(queue_entry.queue_sse_url, timeout_seconds=config.queue_timeout_seconds)
        queue_wait_ms = (time.perf_counter() - queue_wait_started_at) * 1000.0

        if event_name == "expired":
            return FlowResult(
                outcome="queue_expired",
                initial_status=initial.status,
                final_status=408,
                initial_latency_ms=initial_latency_ms,
                total_latency_ms=(time.perf_counter() - start) * 1000.0,
                queue_wait_ms=queue_wait_ms,
                queue_redirect_url=queue_entry.queue_sse_url,
                error="queue_expired",
            )

        if event_name != "allowed":
            return FlowResult(
                outcome="queue_unknown_terminal",
                initial_status=initial.status,
                final_status=None,
                initial_latency_ms=initial_latency_ms,
                total_latency_ms=(time.perf_counter() - start) * 1000.0,
                queue_wait_ms=queue_wait_ms,
                queue_redirect_url=queue_entry.queue_sse_url,
                error=f"unexpected_queue_event_{event_name}",
            )

        token = (payload or {}).get("token")
        if not token:
            return FlowResult(
                outcome="queue_unknown_terminal",
                initial_status=initial.status,
                final_status=None,
                initial_latency_ms=initial_latency_ms,
                total_latency_ms=(time.perf_counter() - start) * 1000.0,
                queue_wait_ms=queue_wait_ms,
                queue_redirect_url=queue_entry.queue_sse_url,
                error="allowed_without_token",
            )

        retry = await http_request(
            "GET",
            config.api_url,
            headers={
                "Accept": "application/json",
                "X-Current-Page-Uri": config.current_page_uri,
                "Authorization": f"Bearer {token}",
            },
            timeout_seconds=config.request_timeout_seconds,
        )
        assert isinstance(retry, HttpResponse)

        outcome = "queue_success" if retry.status == 200 else "queue_retry_failure"
        error = None if retry.status == 200 else f"retry_http_{retry.status}"
        return FlowResult(
            outcome=outcome,
            initial_status=initial.status,
            final_status=retry.status,
            initial_latency_ms=initial_latency_ms,
            total_latency_ms=(time.perf_counter() - start) * 1000.0,
            queue_wait_ms=queue_wait_ms,
            queue_redirect_url=queue_entry.queue_sse_url,
            error=error,
        )
    except asyncio.TimeoutError:
        return FlowResult(
            outcome="client_timeout",
            initial_latency_ms=(time.perf_counter() - start) * 1000.0,
            total_latency_ms=(time.perf_counter() - start) * 1000.0,
            error="timeout",
        )
    except Exception as exc:
        return FlowResult(
            outcome="client_error",
            initial_latency_ms=(time.perf_counter() - start) * 1000.0,
            total_latency_ms=(time.perf_counter() - start) * 1000.0,
            error=exc.__class__.__name__,
        )


def first_query_value(query: dict[str, list[str]], key: str) -> str | None:
    values = query.get(key)
    if not values:
        return None
    return values[0]


def resolve_queue_entry(initial: HttpResponse, config: LoadConfig) -> QueueEntry | None:
    if initial.status == 202:
        return resolve_queue_entry_from_json(initial, config)
    if initial.status in (302, 303, 307, 308):
        return resolve_queue_entry_from_redirect(initial, config)
    return None


def resolve_queue_entry_from_json(initial: HttpResponse, config: LoadConfig) -> QueueEntry | None:
    try:
        payload = json.loads(initial.body.decode("utf-8") or "{}")
    except json.JSONDecodeError:
        return None

    request_id = payload.get("requestId")
    if not request_id:
        return None

    requested_uri = payload.get("requestedUri")
    queue_page_path = payload.get("queuePagePath")
    queue_sse_path = payload.get("queueSsePath")
    queue_sse_url = (
        urljoin(f"{config.queue_origin}/", queue_sse_path)
        if queue_sse_path
        else build_queue_sse_url(config.queue_origin, request_id, requested_uri)
    )

    return QueueEntry(
        request_id=request_id,
        requested_uri=requested_uri,
        queue_page_path=queue_page_path,
        queue_sse_url=queue_sse_url,
    )


def resolve_queue_entry_from_redirect(initial: HttpResponse, config: LoadConfig) -> QueueEntry | None:
    queue_redirect_url = urljoin(config.api_url, initial.headers.get("location", ""))
    queue_parts = urlsplit(queue_redirect_url)
    queue_params = parse_qs(queue_parts.query)
    request_id = first_query_value(queue_params, "requestId")
    if not request_id:
        return None

    requested_uri = first_query_value(queue_params, "requestedUri")
    return QueueEntry(
        request_id=request_id,
        requested_uri=requested_uri,
        queue_page_path=f"{queue_parts.path}?{queue_parts.query}" if queue_parts.query else queue_parts.path,
        queue_sse_url=build_queue_sse_url(config.queue_origin, request_id, requested_uri),
    )


def build_queue_sse_url(web_origin: str, request_id: str, requested_uri: str | None) -> str:
    base = f"{web_origin.rstrip('/')}/turnstile/queue/events?requestId={request_id}"
    if requested_uri:
        from urllib.parse import quote

        base += f"&requestedUri={quote(requested_uri, safe='/?=&')}"
    return base


async def run_stage(stage: StageConfig, config: LoadConfig) -> StageResult:
    result = StageResult(name=stage.name, target_rps=stage.rps, duration_seconds=stage.duration_seconds)
    result.started_at = time.perf_counter()
    launch_tasks: list[asyncio.Task[FlowResult]] = []
    interval_seconds = 1.0 / stage.rps
    next_launch_at = result.started_at
    stop_at = result.started_at + stage.duration_seconds
    sequence = 0

    while True:
        now = time.perf_counter()
        if now >= stop_at:
            break
        sleep_seconds = next_launch_at - now
        if sleep_seconds > 0:
            await asyncio.sleep(sleep_seconds)

        launch_tasks.append(asyncio.create_task(execute_flow(sequence, config)))
        result.launched += 1
        sequence += 1
        next_launch_at += interval_seconds

    for task in asyncio.as_completed(launch_tasks):
        flow_result = await task
        result.record(flow_result)
        completed = sum(result.counters.values())
        if config.verbose and completed % 500 == 0:
            print(
                f"[{stage.name}] completed={completed} "
                f"direct={result.counters.get('direct_success', 0)} "
                f"queue_ok={result.counters.get('queue_success', 0)} "
                f"timeouts={result.counters.get('client_timeout', 0)}",
                flush=True,
            )

    result.finished_at = time.perf_counter()
    return result


def print_stage_summary(stage: StageResult) -> None:
    summary = stage.as_dict()
    print(
        f"{summary['name']}: target={summary['target_rps']} rps, launched={summary['launched']}, "
        f"launch_rps={summary['launch_rps']}, completed_rps={summary['completed_rps']}, "
        f"elapsed={summary['elapsed_seconds']:.2f}s",
        flush=True,
    )
    print(f"  counters={json.dumps(summary['counters'], ensure_ascii=False, sort_keys=True)}", flush=True)
    if summary["initial_latency_ms"]:
        print(f"  initial_latency_ms={json.dumps(summary['initial_latency_ms'], ensure_ascii=False)}", flush=True)
    if summary["queue_wait_latency_ms"]:
        print(f"  queue_wait_latency_ms={json.dumps(summary['queue_wait_latency_ms'], ensure_ascii=False)}", flush=True)
    if summary["total_latency_ms"]:
        print(f"  total_latency_ms={json.dumps(summary['total_latency_ms'], ensure_ascii=False)}", flush=True)
    if summary["errors"]:
        print(f"  errors={json.dumps(summary['errors'], ensure_ascii=False, sort_keys=True)}", flush=True)


async def main_async(args) -> int:
    queue_origin = normalize_origin(args.queue_origin or args.gateway_origin)
    config = LoadConfig(
        gateway_origin=normalize_origin(args.gateway_origin),
        web_origin=normalize_origin(args.web_origin),
        queue_origin=queue_origin,
        api_path=args.api_path,
        current_page_uri=args.current_page_uri,
        queue_timeout_seconds=args.queue_timeout_seconds,
        request_timeout_seconds=args.request_timeout_seconds,
        queue_entry_mode=args.queue_entry_mode,
        verbose=args.verbose,
    )

    stage_results: list[StageResult] = []
    for stage in parse_stages(args.stage):
        print(f"Running {stage.name}: {stage.rps} rps for {stage.duration_seconds:.2f}s", flush=True)
        stage_result = await run_stage(stage, config)
        stage_results.append(stage_result)
        print_stage_summary(stage_result)

    if args.output_json:
        payload = {
            "gateway_origin": config.gateway_origin,
            "web_origin": config.web_origin,
            "queue_origin": config.queue_origin,
            "queue_entry_mode": config.queue_entry_mode,
            "api_url": config.api_url,
            "queue_timeout_seconds": config.queue_timeout_seconds,
            "request_timeout_seconds": config.request_timeout_seconds,
            "stages": [stage.as_dict() for stage in stage_results],
        }
        with open(args.output_json, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
        print(f"Wrote JSON summary to {args.output_json}", flush=True)

    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Load-test the gateway queue flow without Locust. Follows queue admission response -> SSE -> token retry."
    )
    parser.add_argument(
        "--stage",
        action="append",
        default=[],
        help="Stage definition in the format <rps>x<seconds>. Repeat the flag for multiple stages.",
    )
    parser.add_argument("--gateway-origin", default=DEFAULT_GATEWAY_ORIGIN)
    parser.add_argument(
        "--web-origin",
        default=DEFAULT_WEB_ORIGIN,
        help="Legacy React queue page origin. Only used with --queue-entry-mode web-client.",
    )
    parser.add_argument(
        "--queue-origin",
        help="Origin used for the queue SSE endpoint. Defaults to --gateway-origin.",
    )
    parser.add_argument("--api-path", default=DEFAULT_API_PATH)
    parser.add_argument("--current-page-uri", default=DEFAULT_PAGE_URI)
    parser.add_argument("--queue-timeout-seconds", type=float, default=15.0)
    parser.add_argument("--request-timeout-seconds", type=float, default=5.0)
    parser.add_argument(
        "--queue-entry-mode",
        choices=("gateway-sse", "web-client"),
        default=DEFAULT_QUEUE_ENTRY_MODE,
        help="How to enter the queue after the initial redirect. Default bypasses the React page and connects SSE through the gateway.",
    )
    parser.add_argument("--output-json")
    parser.add_argument("--verbose", action="store_true")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if not args.stage:
        args.stage = ["100x2", "5000x2"]
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    raise SystemExit(main())
