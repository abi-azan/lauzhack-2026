#!/usr/bin/env bash
# Public HTTPS address for the backend on this laptop: a Cloudflare quick tunnel (free, no account).
# Phones need HTTPS for Web Bluetooth, push and service workers, and wss:// for the WebSocket.
# The address is random and changes on every start: pass it to the passenger app as ?api=<url>.
set -euo pipefail
PORT="${PORT:-8080}"
BIN="$(command -v cloudflared || echo "$HOME/.local/bin/cloudflared")"
if [ ! -x "$BIN" ]; then
  echo "Downloading cloudflared to $BIN ..."
  mkdir -p "$(dirname "$BIN")"
  curl -fsSL -o "$BIN" https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
  chmod +x "$BIN"
fi
echo "Tunnel to http://localhost:$PORT. Look for the https://*.trycloudflare.com line below."
exec "$BIN" tunnel --no-autoupdate --url "http://localhost:$PORT"
