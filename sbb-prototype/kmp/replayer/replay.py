"""Replay a real SBB disruption into the ticker queue, in fast-forward.

This is a stand-in for the control centre's systems. It is a *producer* only: it writes TickerEvent
JSON into the `ticker.events` Redis stream (or POSTs it to /api/ingest/ticker), exactly like the real
upstream would. The backend doesn't know replays exist.

Default scenario: 17 Sep 2026, 17:22, overhead line fault + power failure between Renens VD and
Allaman on the Lausanne - Genève line (VIA 2195005188970 + 2195005188971, KULT case 24605).

The data is read at runtime from the SBB export in Daten/ at the repo root (or DATA_DIR).

    uv run --with pandas --with openpyxl --with redis python -I replayer/replay.py --speed 60
    uv run ... replay.py --dry-run          # print the timeline, send nothing
    uv run ... replay.py --http https://<backend>   # via HTTP instead of Redis
"""

import argparse
import html
import json
import os
import re
import sys
import time
import uuid
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pandas as pd

DATA_DIR = os.environ.get("DATA_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../Daten"))

VIA_IDS = ["2195005188970", "2195005188971"]  # one disruption, two VIA events; replayed as one incident
SOURCE_ID = VIA_IDS[0]
KULT_ID = 24605

STATIONS = {  # DIDOK → name, for the stations this scenario touches
    8501008: "Genève", 8501120: "Lausanne", 8501118: "Renens VD", 8501035: "Allaman", 8501037: "Morges",
}
LINE = {"from": {"id": "8501120", "name": "Lausanne"}, "to": {"id": "8501008", "name": "Genève"}}
LOCATION = {"from": {"id": "8501118", "name": "Renens VD"}, "to": {"id": "8501035", "name": "Allaman"}}

CAUSES = {  # VIA reason (German) → the four languages
    "Unregelmässigkeit Fahrleitung": {
        "de": "Störung an der Fahrleitung", "fr": "dérangement de la ligne de contact",
        "it": "guasto alla linea di contatto", "en": "a fault with the overhead line",
    },
    "Spannungsausfall momentan": {
        "de": "Stromausfall", "fr": "panne de courant", "it": "interruzione di corrente", "en": "a power failure",
    },
}

MILESTONES = [
    ("zeitpunkt_erstpublikation_reiseempfehlung", "Reiseempfehlung publiziert"),
    ("zeitpunkt_erstpublikation_bahnersatz", "Bahnersatz publiziert"),
]

PHONE = re.compile(r"(\+?\d[\d ./-]{7,}\d)")
ZURICH = ZoneInfo("Europe/Zurich")


def clean_html(s: str) -> str:
    s = html.unescape(str(s))
    s = re.sub(r"<br\s*/?>", "\n", s, flags=re.I)
    s = re.sub(r"<[^>]+>", "", s)
    s = PHONE.sub("[Nr.]", s)
    return re.sub(r"\n{2,}", "\n", s).strip()


def build_timeline():
    """Returns [(datetime, TickerInput-like dict)] sorted by time."""
    via = pd.read_excel(os.path.join(DATA_DIR, "Ereignismeldungen", "Ereignismeldungen Via.xlsx"))
    via = via[via.alea_fall_id.astype(str).isin(VIA_IDS)].sort_values("modified")
    out = []
    seen = set()
    for vid, rows in via.groupby(via.alea_fall_id.astype(str)):
        first = rows.iloc[0]
        reason = str(first.alea_ereignisgrund)
        lines = [x.strip() for x in str(first.stoerungs_liniennummer).split(",") if x.strip() and x.strip() != "nan"]
        base = dict(
            sourceEventId=SOURCE_ID, source="via", reasonCode=str(first.ereignisgrund),
            reasonText=CAUSES.get(reason), line=LINE, location=LOCATION, lines=lines,
        )
        kind = "created" if not out else "updated"
        out.append((pd.Timestamp(first.alea_erstellungszeitpunkt),
                    dict(base, kind=kind, text=f"VIA {vid}: {reason} ({first.alea_ort_strecke})")))
        for col, label in MILESTONES:
            t = rows[col].dropna()
            if len(t) and (vid, col) not in seen:
                seen.add((vid, col))
                out.append((pd.Timestamp(t.min()), dict(base, kind="updated", text=f"VIA {vid}: {label}")))
        end = rows.zeitpunkt_globale_endmeldung.dropna()
        if len(end):
            out.append((pd.Timestamp(end.max()), dict(base, kind="ended", text=f"VIA {vid}: Globale Endmeldung")))

    kult = pd.read_csv(os.path.join(DATA_DIR, "intern-liveticker", "3_liveticker_messages_ohne_Zeilenschaltungen.csv"))
    kult = kult[(kult.ereignisfall_id == KULT_ID) & (kult.sender == "TCC")].sort_values("timestamp")
    for _, m in kult.iterrows():
        text = clean_html(m.message) if isinstance(m.message, str) else ""
        if text:
            out.append((pd.Timestamp(m.timestamp), dict(sourceEventId=SOURCE_ID, source="kult", kind="updated", text=text)))

    out.sort(key=lambda x: x[0])
    # The incident must open first, even if a KULT message carries an earlier minute.
    created = next(i for i, (_, e) in enumerate(out) if e["kind"] == "created")
    out.insert(0, out.pop(created))
    return out


TIME = re.compile(r"\b([01]?\d|2[0-3])([h:])([0-5]\d)\b")


def shift_times(text, t0, wall0, speed):
    """Move clock times in the staff text ("jusqu'à 18h30") onto the demo clock, at the replay speed,
    so they agree with the timestamps everything else shows. Times far from the incident stay as they are."""
    def sub(m):
        t = t0.replace(hour=int(m.group(1)), minute=int(m.group(3)), second=0)
        delta = (t - t0).total_seconds()
        if not -3600 <= delta <= 10 * 3600:
            return m.group(0)
        w = wall0 + timedelta(seconds=delta / speed)
        return f"{w:%H}{m.group(2)}{w:%M}"
    return TIME.sub(sub, text)


# The live demo: 4 curated moments of the real evening, each sent with one keypress, so the presenter
# controls the pace. Each beat lists snippets of the real messages it sends (in their original order).
BEATS = [
    ("Fire on the line", "the incident opens; the phone buzzes from the safety net",
     ["Spannungsausfall momentan", "Unregelmässigkeit Fahrleitung"]),
    ("Chaos in the control centre", "staff shorthand floods in; draft with AI and publish",
     ["Decription de la perturbation", "trafic ferroviaire est interrompu", "18474 rebrousse", "724 rebrousse",
      "TGV 9753", "Ligne de contact à terre", "Pompiers sur place"]),
    ("Buses on the way", "buses ordered and travel advice; publish the alternatives",
     ["Concept en préparation", "14 bus commandés", "Reiseempfehlung publiziert", "4 VTS vont",
      "pas encore possible de voyager", "Police Cantonale", "Bahnersatz publiziert"]),
    ("Line open again", "first trains run; resolve the incident",
     ["Premiers trains", "est terminé", "Liveticker terminé", "Globale Endmeldung"]),
]


def beats(timeline):
    """[(name, hint, [(t, event)])]: the timeline cut into the demo's beats; events in no beat are left out."""
    out = []
    for name, hint, snippets in BEATS:
        evs = [(t, e) for t, e in timeline if any(sn in e["text"] for sn in snippets)]
        out.append((name, hint, evs))
    return out


def send_redis(url, events_json):
    import redis
    r = redis.Redis.from_url(url)
    return lambda ev: r.xadd("ticker.events", {"json": json.dumps(ev, ensure_ascii=False)})


def send_http(base):
    def send(ev):
        req = urllib.request.Request(
            base.rstrip("/") + "/api/ingest/ticker", data=json.dumps(ev, ensure_ascii=False).encode(),
            headers={"content-type": "application/json", **({"X-Admin-Key": os.environ["ADMIN_KEY"]} if os.environ.get("ADMIN_KEY") else {})},
            method="POST",
        )
        urllib.request.urlopen(req, timeout=10).read()
    return send


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--speed", type=float, default=60, help="60 = one real minute per second (default)")
    ap.add_argument("--redis", default=os.environ.get("REDIS_URL", "redis://localhost:6379"))
    ap.add_argument("--http", help="send via POST <url>/api/ingest/ticker instead of Redis")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--beats", action="store_true",
                    help="live demo: send the evening in 4 curated beats, one per Enter, instead of by the clock")
    ap.add_argument("--keep-times", action="store_true", help="don't move clock times in the texts onto the demo clock")
    ap.add_argument("--export-beats", metavar="PATH",
                    help="write the 4 beats as JSON for the server's dashboard demo control, then exit")
    ap.add_argument("--max-minutes", type=float, default=None, help="stop after this many scenario minutes")
    args = ap.parse_args()

    timeline = build_timeline()
    t0 = timeline[0][0]
    if args.export_beats:
        return export_beats(timeline, t0, args.export_beats)
    if args.beats:
        return run_beats(timeline, t0, args)
    if args.dry_run:
        for t, e in timeline:
            print(f"+{(t - t0).total_seconds() / 60:6.1f} min  {e['source']:4} {e['kind']:7} {e['text'][:110]!r}")
        print(f"{len(timeline)} events")
        return

    send = send_http(args.http) if args.http else send_redis(args.redis, None)
    start = time.monotonic()
    wall0 = datetime.now(ZURICH)
    for t, e in timeline:
        minutes = (t - t0).total_seconds() / 60
        if args.max_minutes is not None and minutes > args.max_minutes:
            break
        wait = minutes * 60 / args.speed - (time.monotonic() - start)
        if wait > 0:
            time.sleep(wait)
        ev = dict(e)
        if not args.keep_times:
            ev["text"] = shift_times(ev["text"], t0.to_pydatetime(), wall0, args.speed)
        if not args.http:  # straight onto the stream: fill in what the ingest endpoint would
            ev["id"] = "tk-" + uuid.uuid4().hex[:8]
            ev["ts"] = datetime.now(ZURICH).replace(microsecond=0).isoformat()
        send(ev)
        print(f"+{minutes:6.1f} min  sent {e['source']} {e['kind']}: {e['text'][:80]!r}", flush=True)


def export_beats(timeline, t0, path):
    """The beats for the server's demo control (the dashboard's "Next beat"): texts unshifted, plus t0,
    so the server moves their clock times onto the demo clock the same way shift_times does."""
    out = {"t0": t0.isoformat(), "beats": [
        {"name": name, "hint": hint, "events": [dict(e, offsetMin=round((t - t0).total_seconds() / 60, 2)) for t, e in evs]}
        for name, hint, evs in beats(timeline)]}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1, default=str)
    print(f"wrote {path}: " + ", ".join(f"{b['name']} ({len(b['events'])})" for b in out["beats"]))


def run_beats(timeline, t0, args):
    plan = beats(timeline)
    if args.dry_run:
        for i, (name, hint, evs) in enumerate(plan, 1):
            print(f"Beat {i}: {name} ({len(evs)} messages): {hint}")
            for t, e in evs:
                print(f"    +{(t - t0).total_seconds() / 60:6.1f} min  {e['source']:4} {e['text'][:90]!r}")
        return
    send = send_http(args.http) if args.http else send_redis(args.redis, None)
    wall0 = None
    for i, (name, hint, evs) in enumerate(plan, 1):
        try:
            input(f"\n[Enter] Beat {i}/{len(plan)}: {name} ({len(evs)} messages). Then: {hint} ")
        except EOFError:
            pass
        # Clock times inside the texts keep their real distance from the start of the incident.
        wall0 = wall0 or datetime.now(ZURICH)
        for t, e in evs:
            ev = dict(e)
            if not args.keep_times:
                ev["text"] = shift_times(ev["text"], t0.to_pydatetime(), wall0, 1)
            if not args.http:
                ev["id"] = "tk-" + uuid.uuid4().hex[:8]
                ev["ts"] = datetime.now(ZURICH).replace(microsecond=0).isoformat()
            send(ev)
            print(f"   sent {e['source']} {e['kind']}: {ev['text'][:80]!r}", flush=True)
            time.sleep(0.7)  # so the dashboard's ticker visibly fills up
    print("\nAll beats sent. Resolve the incident in the dashboard.")


if __name__ == "__main__":
    sys.exit(main())
