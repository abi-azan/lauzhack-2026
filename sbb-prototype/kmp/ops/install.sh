#!/usr/bin/env bash
# Installs the live demo as systemd *user* services, so it restarts on any crash and comes back after a
# reboot, independent of terminals and Claude sessions:
#   railstatus-server   the backend (:8080), Restart=always
#   railstatus-tunnel   the Cloudflare quick tunnel, Restart=always (metrics on :20241 for the address)
#   railstatus-awake    blocks sleep and the lid switch while the demo is installed
#   railstatus-watchdog (timer, every minute) heals backend/tunnel and announces a new public address
# Redis gets Docker's restart policy. Run it from the checkout the demo should serve: ./demo install
# Optional keys live in the gitignored .demo/: ojp_key (real routes for next move), openrouter_key.
set -euo pipefail
KMP="$(cd "$(dirname "$0")/.." && pwd)"; D="$KMP/.demo"; U="$HOME/.config/systemd/user"
ROOT="$(cd "$KMP/../.." && pwd)"; mkdir -p "$D" "$U"
JAVA="${JAVA_HOME:-$(ls -d "$HOME"/.local/share/jdks/jdk-21* 2>/dev/null | head -1)}"
KEY="$(tr -d '[:space:]' < "$ROOT/openrouter key.txt" 2>/dev/null || cat "$D/openrouter_key" 2>/dev/null || true)"
CF="$(command -v cloudflared || echo "$HOME/.local/bin/cloudflared")"

cat > "$D/server.env" <<ENV
JAVA_HOME=$JAVA
PORT=8080
BUS=auto
AUTO_ACK_SECONDS=${AUTO_ACK_SECONDS:-15}
TRAINS_ANCHOR=${TRAINS_ANCHOR:-17:30}
OPENROUTER_API_KEY=$KEY
ADMIN_KEY=${ADMIN_KEY:-}
OJP_KEY=$(cat "$D/ojp_key" 2>/dev/null || echo "${OJP_KEY:-}")
DRAFT_ENGINE=$(cat "$D/draft_engine" 2>/dev/null || echo "${DRAFT_ENGINE:-}")
STATIC=/=$ROOT/sbb-prototype/native/composeApp/build/dist/wasmJs/productionExecutable,/admin=$KMP/admin/build/dist/wasmJs/productionExecutable
ENV
chmod 600 "$D/server.env"

unit() { cat > "$U/$1"; }
unit railstatus-server.service <<UNIT
[Unit]
Description=Rail Status demo backend
[Service]
WorkingDirectory=$KMP
EnvironmentFile=$D/server.env
ExecStart=$KMP/server/build/install/server/bin/server
StandardOutput=append:$D/server.log
StandardError=append:$D/server.log
Restart=always
RestartSec=3
[Install]
WantedBy=default.target
UNIT
unit railstatus-tunnel.service <<UNIT
[Unit]
Description=Rail Status demo HTTPS tunnel
After=railstatus-server.service
[Service]
ExecStart=$CF tunnel --no-autoupdate --metrics localhost:20241 --url http://localhost:8080
StandardOutput=append:$D/tunnel.log
StandardError=append:$D/tunnel.log
Restart=always
RestartSec=5
[Install]
WantedBy=default.target
UNIT
unit railstatus-awake.service <<UNIT
[Unit]
Description=Keep the laptop awake for the Rail Status demo
[Service]
ExecStart=/usr/bin/systemd-inhibit --what=sleep:idle:handle-lid-switch --who=railstatus --why=live-demo sleep infinity
Restart=always
[Install]
WantedBy=default.target
UNIT
unit railstatus-watchdog.service <<UNIT
[Unit]
Description=Rail Status demo watchdog
[Service]
Type=oneshot
Environment=PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=$KMP/ops/watchdog.sh
UNIT
unit railstatus-watchdog.timer <<UNIT
[Unit]
Description=Run the Rail Status watchdog every minute
[Timer]
OnBootSec=1min
OnUnitActiveSec=1min
[Install]
WantedBy=timers.target
UNIT

(cd "$KMP" && docker compose up -d redis >/dev/null 2>&1) || true
docker update --restart unless-stopped "$(cd "$KMP" && docker compose ps -q redis)" >/dev/null 2>&1 || echo "! couldn't set Redis' restart policy"
systemctl --user daemon-reload
systemctl --user enable --now railstatus-server railstatus-tunnel railstatus-awake railstatus-watchdog.timer
systemctl --user restart railstatus-server   # pick up a changed server.env; the tunnel (and its address) stays
loginctl enable-linger "$USER" 2>/dev/null && echo "linger on: services also run without a login session" \
  || echo "! couldn't enable linger: services start when you log in. Fix once with: sudo loginctl enable-linger $USER"
echo "installed. Check with ./demo status"
