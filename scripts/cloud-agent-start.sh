#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGDIR="${TEAM_JEOPARDY_LOGDIR:-/tmp/team-jeopardy}"
mkdir -p "$LOGDIR"

wait_http() {
  local url="$1"
  local name="$2"
  local tries="${3:-90}"
  for _ in $(seq 1 "$tries"); do
    if curl -sf "$url" >/dev/null; then
      echo "$name ready"
      return 0
    fi
    sleep 1
  done
  echo "$name failed to become ready at $url" >&2
  if [[ -f "$LOGDIR/${name}.log" ]]; then
    tail -n 80 "$LOGDIR/${name}.log" >&2 || true
  fi
  return 1
}

if ! curl -sf http://127.0.0.1:8080/api/health >/dev/null; then
  if [[ -f "$LOGDIR/server.pid" ]] && kill -0 "$(cat "$LOGDIR/server.pid")" 2>/dev/null; then
    echo "server pid alive but health not ready; waiting"
  else
    (
      cd "$ROOT/server"
      nohup mvn -q spring-boot:run >"$LOGDIR/server.log" 2>&1 &
      echo $! >"$LOGDIR/server.pid"
    )
  fi
fi
wait_http http://127.0.0.1:8080/api/health server 90

if ! curl -sf http://127.0.0.1:5173 >/dev/null; then
  if [[ -f "$LOGDIR/client.pid" ]] && kill -0 "$(cat "$LOGDIR/client.pid")" 2>/dev/null; then
    echo "client pid alive but not ready; waiting"
  else
    (
      cd "$ROOT/client"
      nohup npm run dev -- --host 0.0.0.0 --port 5173 >"$LOGDIR/client.log" 2>&1 &
      echo $! >"$LOGDIR/client.pid"
    )
  fi
fi
wait_http http://127.0.0.1:5173 client 60

echo "Team Jeopardy server http://127.0.0.1:8080 and client http://127.0.0.1:5173"
