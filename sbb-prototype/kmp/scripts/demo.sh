#!/usr/bin/env bash
# Runs the whole demo on this laptop, detached from any terminal, so closing a shell doesn't kill it:
#   Redis (docker) -> backend (:8080, serves the passenger app at / and the dashboard at /admin/)
#   (with the passenger chat at /api/chat) -> HTTPS tunnel -> keeps the laptop awake.
#
#   scripts/demo.sh start [--build]   start whatever isn't running (--build rebuilds server, dashboard, app)
#   scripts/demo.sh stop              stop what this script started (Redis and a reused tunnel stay up)
#   scripts/demo.sh restart           stop + start (the backend's in-memory state is cleared)
#   scripts/demo.sh status            what's up, plus the public address
#   scripts/demo.sh tunnel            (re)start our own detached tunnel; a quick tunnel gets a NEW address
#
# Env: TUNNEL_TOKEN + PUBLIC_URL  use a named Cloudflare tunnel with a stable address instead of a quick one
#      OPENROUTER_API_KEY         else read from .demo/openrouter_key (gitignored); enables the AI parts
#      TRAINS_ANCHOR              default 17:30 (the 17 Sep demo)
#      AUTO_ACK_SECONDS           safety net; default 15 for the 3-minute demo (the server alone defaults to 60)
set -uo pipefail

KMP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$KMP/../.." && pwd)"
D="$KMP/.demo"
mkdir -p "$D"
cd "$KMP"

if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(17|21)'; then
  JAVA_HOME="$(ls -d "$HOME"/.local/share/jdks/jdk-21* 2>/dev/null | head -1)"
fi
export JAVA_HOME

up() { curl -sf -m 2 "$1" >/dev/null; }
alive() { [ -f "$D/$1.pid" ] && kill -0 "$(cat "$D/$1.pid")" 2>/dev/null; }
# setsid: own session, so the process survives the shell (and the Claude session) that started it.
launch() { local name=$1; shift; setsid nohup "$@" >"$D/$name.log" 2>&1 < /dev/null & echo $! >"$D/$name.pid"; }
wait_for() { for _ in $(seq 1 "${2:-60}"); do up "$1" && return 0; sleep 1; done; return 1; }

tunnel_url() {
  if [ -n "${PUBLIC_URL:-}" ]; then echo "$PUBLIC_URL"; return; fi
  for p in 20241 20242 20243 20244 20245; do
    h=$(curl -s -m 2 "localhost:$p/quicktunnel" | sed -n 's/.*"hostname":"\([^"]*\)".*/\1/p')
    [ -n "$h" ] && { echo "https://$h"; return; }
  done
}

start_tunnel() {
  local bin; bin="$(command -v cloudflared || echo "$HOME/.local/bin/cloudflared")"
  if [ ! -x "$bin" ]; then
    curl -fsSL -o "$bin" https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 && chmod +x "$bin"
  fi
  if [ -n "${TUNNEL_TOKEN:-}" ]; then
    launch tunnel "$bin" tunnel --no-autoupdate run --token "$TUNNEL_TOKEN"
  else
    launch tunnel "$bin" tunnel --no-autoupdate --url http://localhost:8080
  fi
  for _ in $(seq 1 30); do [ -n "$(tunnel_url)" ] && return 0; sleep 1; done
}

build() {
  echo "building server + dashboard ..."
  ./gradlew -q :server:installDist :admin:wasmJsBrowserDistribution || exit 1
  echo "building passenger app (Kotlin, web) ..."
  (cd "$ROOT/sbb-prototype/native" && ./gradlew -q :composeApp:wasmJsBrowserDistribution) || exit 1
}

WEBAPP="$ROOT/sbb-prototype/native/composeApp/build/dist/wasmJs/productionExecutable"

start() {
  { [ "${1:-}" = "--build" ] || [ ! -x server/build/install/server/bin/server ] || [ ! -d admin/build/dist/wasmJs/productionExecutable ] \
    || [ ! -d "$WEBAPP" ]; } && build

  docker compose up -d redis >/dev/null 2>&1 || echo "! Redis didn't start; the backend falls back to its in-memory queue"

  local or="${OPENROUTER_API_KEY:-}"
  [ -z "$or" ] && [ -f "$D/openrouter_key" ] && or=$(cat "$D/openrouter_key")
  [ -z "$or" ] && [ -f "$ROOT/openrouter key.txt" ] && or=$(tr -d '[:space:]' <"$ROOT/openrouter key.txt")

  if up localhost:8080/health; then echo "backend  already up"
  else
    launch server env OPENROUTER_API_KEY="$or" ADMIN_KEY="${ADMIN_KEY:-}" BUS=auto AUTO_ACK_SECONDS="${AUTO_ACK_SECONDS:-15}" TRAINS_ANCHOR="${TRAINS_ANCHOR:-17:30}" \
      STATIC="/=$WEBAPP,/admin=$KMP/admin/build/dist/wasmJs/productionExecutable" \
      server/build/install/server/bin/server
    wait_for localhost:8080/health 60 && echo "backend  started" || { echo "! backend didn't come up, see $D/server.log"; exit 1; }
  fi

  if [ -n "$(tunnel_url)" ] && [ -z "${TUNNEL_TOKEN:-}" ]; then echo "tunnel   already up (reused)"
  else start_tunnel && echo "tunnel   started" || echo "! tunnel didn't come up, see $D/tunnel.log"
  fi

  if ! alive inhibit; then
    launch inhibit systemd-inhibit --what=sleep:idle:handle-lid-switch --who=railstatus --why="live demo" sleep infinity
    echo "sleep    blocked while the demo runs"
  fi
  status
}

stop() {
  # "chat" stops a Node chat service an older version of this script started.
  for n in tunnel server chat inhibit; do
    if alive "$n"; then kill "$(cat "$D/$n.pid")" && echo "stopped $n"; fi
    rm -f "$D/$n.pid"
  done
}

status() {
  for s in "backend localhost:8080/health" "chat localhost:8080/api/chat/health"; do
    set -- $s; up "$2" && echo "$1: up" || echo "$1: DOWN"
  done
  alive inhibit && echo "sleep: blocked" || echo "sleep: allowed"
  local url; url=$(tunnel_url)
  if [ -z "$url" ]; then echo "tunnel: DOWN"; return; fi
  echo "$url" >"$D/url"
  echo
  echo "Passenger app   $url/"
  echo "Check-in link   $url/#/checkin/IR90-1734?car=7"
  echo "Dashboard       $url/admin/"
  echo "Run the demo     ./demo run    (from the repo root; ./demo for all commands)"
  # QR code for the check-in link, in the terminal and as an SVG to print.
  uv run -q --with qrcode python -I -c '
import sys, qrcode, qrcode.image.svg
url = sys.argv[1]
q = qrcode.QRCode(border=1); q.add_data(url); q.print_ascii(invert=True)
qrcode.make(url, image_factory=qrcode.image.svg.SvgPathImage).save(sys.argv[2])
print("QR saved to", sys.argv[2])' "$url/#/checkin/IR90-1734?car=7" "$D/checkin-qr.svg" 2>/dev/null || true
}

case "${1:-status}" in
  start) start "${2:-}" ;;
  stop) stop ;;
  restart) stop; sleep 1; start "${2:-}" ;;
  status) status ;;
  build) build ;;
  tunnel) alive tunnel && kill "$(cat "$D/tunnel.pid")"; sleep 1; start_tunnel; status ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
