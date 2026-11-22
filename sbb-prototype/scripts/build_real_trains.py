#!/usr/bin/env python3
"""Build the real trains of the 1 Sep 2026 Ostermundigen incident (Bern – Thun) for the replay.

Inputs are open data only (opentransportdata.swiss):
  * the GTFS timetable 2026 (gtfs.zip)                -> planned trains, stops, platforms
  * the Ist-Daten v2 archive for Sept 2026 (.zip)     -> actual times and cancellations
No SBB Discord / SBB-export data is read, so the output can be committed.

usage:
  python3 build_real_trains.py --gtfs gtfs.zip --ist ist-daten-v2-2026-09.zip \\
      --out ../kmp/data/real-trains-2026-09-01.json

The output follows the backend's `Train` shape (sbb-prototype/kmp/shared/.../Model.kt):
  {id, product, line, number, direction, stops:[{station:{id,name}, arr, dep, platform}], cars}
plus extra fields the backend can ignore: per stop `arrActual`, `depActual`, `cancelled`,
`passThrough`; per train `cancelled`, `planned` (first/last planned time), `operator`.
"""
import argparse, csv, datetime as dt, io, json, re, sys, zipfile
from collections import defaultdict

DAY = "2026-09-01"
WINDOW = ("03:30", "09:30")              # incident 04:06 – 08:02 local, plus margin
BERN, THUN = "8507000", "8507100"
# Stations on the Bern – Thun main line via Gümligen (not the Belp line).
MAINLINE = {"8507002": "Ostermundigen", "8507003": "Gümligen", "8507004": "Rubigen",
            "8507005": "Münsingen", "8507006": "Wichtrach", "8507007": "Kiesen", "8507008": "Uttigen"}
BELP_LINE = {"8507082", "8507083", "8507084", "8507085", "8507086", "8507087"}  # Belp line stops
CARS = {"IC": 10, "ICE": 12, "IR": 8, "RE": 6, "S": 4, "EC": 10}


def rows(z, name):
    with z.open(name) as f:
        yield from csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig"))


def hhmm(t):
    if not t:
        return None
    h, m = int(t[:2]), int(t[3:5])
    return f"{h % 24:02d}:{m:02d}"


def active_services(z, day):
    d = day.replace("-", "")
    wd = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"][dt.date.fromisoformat(day).weekday()]
    on = {r["service_id"] for r in rows(z, "calendar.txt")
          if r["start_date"] <= d <= r["end_date"] and r[wd] == "1"}
    for r in rows(z, "calendar_dates.txt"):
        if r["date"] == d:
            (on.add if r["exception_type"] == "1" else on.discard)(r["service_id"])
    return on


def load_gtfs(path):
    z = zipfile.ZipFile(path)
    print("stops…", file=sys.stderr)
    stop = {}        # stop_id -> (didok, name, platform)
    for r in rows(z, "stops.txt"):
        didok = (r.get("didok") or r["original_stop_id"] or "").split(":")[0]
        stop[r["stop_id"]] = (didok, r["stop_name"], r.get("platform_code") or None)
    watch = {sid for sid, (d, _, _) in stop.items() if d in {BERN, THUN, *MAINLINE}}

    print("services…", file=sys.stderr)
    services = active_services(z, DAY)
    print("trips…", file=sys.stderr)
    routes = {r["route_id"]: r for r in rows(z, "routes.txt")}
    trips = {}
    for r in rows(z, "trips.txt"):
        if r["service_id"] in services:
            trips[r["trip_id"]] = r

    print("stop_times (one pass, trips are contiguous)…", file=sys.stderr)
    st = {}
    cur, buf, hit = None, [], False

    def flush():
        if cur is not None and hit and cur in trips:
            st[cur] = buf

    for r in rows(z, "stop_times.txt"):
        tid = r["trip_id"]
        if tid != cur:
            flush()
            cur, buf, hit = tid, [], False
        buf.append(r)
        if r["stop_id"] in watch:
            hit = True
    flush()
    print(f"{len(st)} trips touch Bern/Thun/main line", file=sys.stderr)

    out = []
    for tid, srows in st.items():
        srows.sort(key=lambda r: int(r["stop_sequence"]))
        didoks = [stop[r["stop_id"]][0] for r in srows]
        if set(didoks) & BELP_LINE:
            continue
        # must traverse Bern <-> Thun on the main line: either stop at a mainline station,
        # or run Bern and Thun as consecutive stops (IC non-stop via Gümligen)
        consecutive = any({a, b} == {BERN, THUN} for a, b in zip(didoks, didoks[1:]))
        if not (set(didoks) & set(MAINLINE) or consecutive):
            continue
        times = [hhmm(r["departure_time"] or r["arrival_time"]) for r in srows
                 if stop[r["stop_id"]][0] in {BERN, THUN, *MAINLINE}]
        if not any(WINDOW[0] <= t <= WINDOW[1] for t in times if t):
            continue
        trip, route = trips[tid], routes[trips[tid]["route_id"]]
        line_code = route["route_short_name"] or route["route_desc"]
        product = route["route_desc"] or re.sub(r"\d+", "", line_code)
        number = trip["trip_short_name"]
        stops = []
        for i, r in enumerate(srows):
            didok, name, platform = stop[r["stop_id"]]
            stops.append({
                "station": {"id": didok, "name": name},
                "arr": None if i == 0 else hhmm(r["arrival_time"]),
                "dep": None if i == len(srows) - 1 else hhmm(r["departure_time"]),
                "platform": platform,
            })
        out.append({
            "id": f"{line_code}-{number}",
            "product": product,
            "line": re.sub(r"^([A-Z]+)(\d+)$", r"\1 \2", line_code),
            "number": number,
            "direction": trip["trip_headsign"],
            "stops": stops,
            "cars": CARS.get(product, 6),
            "operator": route["agency_id"],
        })
    return out


def apply_ist(trains, path):
    """Merge actual times / cancellations from the Ist-Daten v2 day file into the trains."""
    z = zipfile.ZipFile(path)
    names = [n for n in z.namelist() if DAY in n or DAY.replace("-", "") in n]
    if not names:
        sys.exit(f"no file for {DAY} in {path}; contents start with {z.namelist()[:5]}")
    print(f"ist-daten: {names[0]}", file=sys.stderr)
    want = {(t["number"], s["station"]["id"]) for t in trains for s in t["stops"]}
    numbers = {t["number"] for t in trains}
    actual = {}
    with z.open(names[0]) as f:
        text = io.TextIOWrapper(f, encoding="utf-8-sig")
        head = text.readline()
        delim = ";" if head.count(";") > head.count(",") else ","
        cols = [c.strip().strip('"') for c in head.strip().split(delim)]
        for rec in csv.reader(text, delimiter=delim):
            r = dict(zip(cols, rec))
            num = (r.get("LINIEN_ID") or "").strip()
            if num not in numbers:
                continue
            key = (num, (r.get("BPUIC") or "").strip())
            if key not in want:
                continue
            actual[key] = r

    def t(v):
        m = re.search(r"(\d{2}):(\d{2})", v or "")
        return f"{m.group(1)}:{m.group(2)}" if m else None

    matched = 0
    for tr in trains:
        hits = 0
        for s in tr["stops"]:
            r = actual.get((tr["number"], s["station"]["id"]))
            if not r:
                continue
            hits += 1
            s["arrActual"] = t(r.get("AN_PROGNOSE")) if r.get("AN_PROGNOSE_STATUS", "").upper() in ("REAL", "GESCHAETZT", "PROGNOSE") else None
            s["depActual"] = t(r.get("AB_PROGNOSE")) if r.get("AB_PROGNOSE_STATUS", "").upper() in ("REAL", "GESCHAETZT", "PROGNOSE") else None
            s["cancelled"] = (r.get("FAELLT_AUS_TF") or "").lower() == "true"
            s["passThrough"] = (r.get("DURCHFAHRT_TF") or "").lower() == "true"
        tr["cancelled"] = hits > 0 and all(s.get("cancelled") for s in tr["stops"] if "cancelled" in s)
        matched += hits > 0
    print(f"actual data matched for {matched}/{len(trains)} trains", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gtfs", required=True)
    ap.add_argument("--ist")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    cache = a.out + ".planned-cache.json"
    try:
        trains = json.load(open(cache))
        print(f"using cached GTFS stage {cache}", file=sys.stderr)
    except (OSError, ValueError):
        trains = load_gtfs(a.gtfs)
        json.dump(trains, open(cache, "w"), ensure_ascii=False)
    def key(t):
        ts = [s["dep"] or s["arr"] for s in t["stops"] if s["station"]["id"] in {BERN, THUN, *MAINLINE}]
        return min(x for x in ts if x) if any(ts) else "99:99"
    trains.sort(key=key)
    if a.ist:
        apply_ist(trains, a.ist)
    doc = {
        "date": DAY,
        "incident": "Bern – Thun, Ostermundigen – Gümligen interrupted (person accident), about 04:06 – 08:02",
        "sources": ["GTFS Timetable 2026, opentransportdata.swiss",
                    "Ist-Daten v2 2026-09, opentransportdata.swiss" if a.ist else None],
        "trains": trains,
    }
    with open(a.out, "w") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    print(f"wrote {len(trains)} trains to {a.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
