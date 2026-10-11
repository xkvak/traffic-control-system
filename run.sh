#!/usr/bin/env bash
# 전체 서비스를 띄우고, 좌석 예매 데모와 Locust 화면을 브라우저로 연다.
#   ./run.sh                   # 추가 인자는 locust로 넘어간다 (예: ./run.sh --processes 4)
# Ctrl+C는 Locust만 멈춘다. 서비스까지 끄려면 docker compose down
set -euo pipefail
cd "$(dirname "$0")"

for cmd in docker uvx curl; do
  command -v "$cmd" >/dev/null || { echo "$cmd 가 필요합니다" >&2; exit 1; }
done

wait_for() {  # URL이 응답할 때까지 최대 120초 기다린다
  for _ in $(seq 240); do curl -sf "$1" >/dev/null && return; sleep 0.5; done
  echo "$1 이 응답하지 않습니다. docker compose logs 로 확인하세요" >&2
  exit 1
}
open_url() { open "$1" 2>/dev/null || xdg-open "$1" 2>/dev/null || echo "브라우저에서 여세요: $1"; }

docker compose up --build -d
wait_for http://localhost:8083/health
wait_for http://localhost:8080/health
wait_for http://localhost:5173
open_url http://localhost:5173
(wait_for http://localhost:8089 && open_url http://localhost:8089) &

exec uvx --from locust==2.46.7 --with "pyjwt[crypto]" \
  locust -f examples/locust/locustfile.py --class-picker "$@"
