#!/usr/bin/env bash
# Runs every minute (railstatus-watchdog.timer). Keeps the public demo reachable:
#  - backend down locally      -> restart railstatus-server
#  - public address unreachable twice in a row -> restart railstatus-tunnel (a quick tunnel gets a new address)
#  - the address changed       -> write .demo/url, regenerate the QR code, post the new address on the team board once
KMP="$(cd "$(dirname "$0")/.." && pwd)"; D="$KMP/.demo"; mkdir -p "$D"
log() { echo "$(date '+%F %T') $*" >> "$D/watchdog.log"; }
up() { curl -sf -m "${2:-5}" "$1" >/dev/null; }

if ! up http://localhost:8080/health; then
  log "backend down, restarting railstatus-server"; systemctl --user restart railstatus-server; exit 0
fi

url=""
for p in 20241 20242 20243 20244 20245; do
  h=$(curl -s -m 2 "localhost:$p/quicktunnel" | sed -n 's/.*"hostname":"\([^"]*\)".*/\1/p'); [ -n "$h" ] && { url="https://$h"; break; }
done
if [ -z "$url" ] || ! up "$url/health" 15; then
  n=$(( $(cat "$D/tunnel_fail" 2>/dev/null || echo 0) + 1 )); echo $n > "$D/tunnel_fail"
  log "public address ${url:-none} unreachable ($n)"
  if [ "$n" -ge 2 ]; then log "restarting railstatus-tunnel"; systemctl --user restart railstatus-tunnel; echo 0 > "$D/tunnel_fail"; fi
  exit 0
fi
echo 0 > "$D/tunnel_fail"

if [ "$url" != "$(cat "$D/url" 2>/dev/null)" ]; then
  echo "$url" > "$D/url"; log "new public address $url"
  uv run -q --with qrcode python -I -c 'import sys,qrcode,qrcode.image.svg; qrcode.make(sys.argv[1],image_factory=qrcode.image.svg.SvgPathImage).save(sys.argv[2])' \
    "$url/#/checkin/IR90-1734?car=7" "$D/checkin-qr.svg" 2>/dev/null
  gh issue comment 1 --repo bayes-boys/lauzhack-2026 --body "**[watchdog on Emil's laptop] STATUS: the live demo has a NEW address: $url** (dashboard at \`/admin/\`). The tunnel restarted on its own; point phones and the QR code here." >/dev/null 2>&1 \
    && log "posted the new address" || log "couldn't post the new address"
fi
