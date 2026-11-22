"""Touch-timetable tile backgrounds: a dark map snippet around each station, in SBB Mobile's style.

    python3 -I touch_tiles.py fetch  <cache-dir>              # z14 vector tiles, sequential, cached
    python3 -I touch_tiles.py render <cache-dir> <out-dir>    # <out-dir>/<stationId>.webp

Data: OpenStreetMap via OpenFreeMap's z14 vector tiles (OpenMapTiles schema), like assets/map/build-basemap.mjs.
Map data © OpenStreetMap contributors (ODbL). Only tile coordinates are requested. Needs Pillow with WebP.
(Overpass, the first choice, refused connections from the build machine on 22 Nov; the tiles carry the same ways.)
"""
import gzip
import json
import math
import os
import sys
import time
import urllib.request

# id: (lat, lon) of the crop centre: SBB centres each tile on the station; these are fitted to the capture of SBB's
# tiles by cross-correlation (within ~20 m of the station coordinates in data/Stations.kt).
STATIONS = {
    "bern": (46.94885, 7.43913),
    "lausanne": (46.51679, 6.62912),
    "olten": (47.35191, 7.90769),
    "geneve": (46.21019, 6.14240),
    "renens": (46.53711, 6.57862),
    "fribourg": (46.80317, 7.15105),
    "basel": (47.54742, 7.58957),
    "zuerich": (47.37817, 8.54025),
    "luzern": (47.05020, 8.31018),
    "winterthur": (47.50035, 8.72383),
    "st-gallen": (47.42321, 9.36992),
    "lugano": (46.00551, 8.94701),
}

# Tile: 196.8 x 82.3 dp. SBB's map shows ~2.98 m per dp (measured on a 2.625-density capture).
TILE_DP = (196.8, 82.3)
M_PER_DP = 2.98
OUT_SCALE = 3.05  # px per dp in the output: 600 x 251 px
SS = 4  # supersampling for anti-aliasing

# Colours and widths (dp), matched to the capture.
BG = (43, 56, 86)
WATER = (63, 79, 112)
STREET = (58, 71, 101)
STREET_MAJOR = (63, 76, 107)
PATH = (52, 65, 96)
RAIL = (136, 144, 160)
TRAM = (78, 89, 117)
W_MAJOR, W_STREET, W_SERVICE, W_PATH = 2.0, 1.7, 1.2, 0.75
W_RAIL, W_TRAM = 1.1, 0.9

Z = 14
TILEJSON = "https://tiles.openfreemap.org/planet"
UA = {"User-Agent": "touch-tile-renderer/1.0"}


# ---------------------------------------------------------------- geometry helpers

def tile_xy(lat, lon, z=Z):
    n = 2 ** z
    r = math.radians(lat)
    return (int((lon + 180) / 360 * n), int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n))


def tiles_for(lat, lon, half_w_m, half_h_m):
    dlat = half_h_m / 111_320
    dlon = half_w_m / (111_320 * math.cos(math.radians(lat)))
    x0, y0 = tile_xy(lat + dlat, lon - dlon)
    x1, y1 = tile_xy(lat - dlat, lon + dlon)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


# Fetch area around each centre: the tile plus room to re-frame it (and for the alignment check).
FETCH_HALF_M = (TILE_DP[0] * M_PER_DP * 2.0, TILE_DP[1] * M_PER_DP * 2.5)


# ---------------------------------------------------------------- fetch

def fetch(cache):
    os.makedirs(cache, exist_ok=True)
    with urllib.request.urlopen(urllib.request.Request(TILEJSON, headers=UA), timeout=30) as r:
        tmpl = json.load(r)["tiles"][0]
    for sid, (lat, lon) in STATIONS.items():
        for x, y in tiles_for(lat, lon, *FETCH_HALF_M):
            out = os.path.join(cache, f"{Z}_{x}_{y}.pbf")
            if os.path.exists(out):
                continue
            url = tmpl.replace("{z}", str(Z)).replace("{x}", str(x)).replace("{y}", str(y))
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                body = r.read()
            if body[:2] == b"\x1f\x8b":
                body = gzip.decompress(body)
            with open(out, "wb") as f:
                f.write(body)
            print(sid, x, y, len(body), "bytes", flush=True)
            time.sleep(0.5)


# ---------------------------------------------------------------- minimal Mapbox Vector Tile decoder

def _varint(b, i):
    v = s = 0
    while True:
        c = b[i]
        i += 1
        v |= (c & 0x7F) << s
        s += 7
        if c < 0x80:
            return v, i


def _fields(b):
    i = 0
    while i < len(b):
        key, i = _varint(b, i)
        f, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _varint(b, i)
        elif wt == 2:
            ln, i = _varint(b, i)
            v = b[i:i + ln]
            i += ln
        elif wt == 1:
            v = b[i:i + 8]
            i += 8
        elif wt == 5:
            v = b[i:i + 4]
            i += 4
        else:
            raise ValueError(f"wire type {wt}")
        yield f, wt, v


def _packed(b):
    out, i = [], 0
    while i < len(b):
        v, i = _varint(b, i)
        out.append(v)
    return out


def _value(b):
    import struct
    for f, wt, v in _fields(b):
        if f == 1:
            return bytes(v).decode()
        if f == 2:
            return struct.unpack("<f", v)[0]
        if f == 3:
            return struct.unpack("<d", v)[0]
        if f in (4, 5):
            return v
        if f == 6:
            return (v >> 1) ^ -(v & 1)
        if f == 7:
            return bool(v)
    return None


def _geometry(cmds):
    """Decode a geometry command stream into a list of parts (lists of (x, y) in tile units)."""
    parts, cur, x, y, i = [], None, 0, 0, 0
    while i < len(cmds):
        cmd, count = cmds[i] & 7, cmds[i] >> 3
        i += 1
        if cmd == 7:  # ClosePath
            if cur:
                cur.append(cur[0])
            continue
        for _ in range(count):
            dx, dy = cmds[i], cmds[i + 1]
            i += 2
            x += (dx >> 1) ^ -(dx & 1)
            y += (dy >> 1) ^ -(dy & 1)
            if cmd == 1:  # MoveTo
                cur = [(x, y)]
                parts.append(cur)
            else:
                cur.append((x, y))
    return parts


def decode_tile(buf, layers):
    """{layer: [(props, geomtype, parts)]} for the wanted layers."""
    out = {}
    for f, _, lb in _fields(buf):
        if f != 3:
            continue
        name, feats, keys, vals, extent = None, [], [], [], 4096
        for lf, _, lv in _fields(lb):
            if lf == 1:
                name = bytes(lv).decode()
            elif lf == 2:
                feats.append(lv)
            elif lf == 3:
                keys.append(bytes(lv).decode())
            elif lf == 4:
                vals.append(_value(lv))
            elif lf == 5:
                extent = lv
        if name not in layers:
            continue
        res = out.setdefault(name, [])
        for fb in feats:
            tags, gtype, geom = [], 0, []
            for ff, _, fv in _fields(fb):
                if ff == 2:
                    tags = _packed(fv)
                elif ff == 3:
                    gtype = fv
                elif ff == 4:
                    geom = _packed(fv)
            props = {keys[tags[k]]: vals[tags[k + 1]] for k in range(0, len(tags), 2)}
            res.append((props, gtype, [[(px / extent, py / extent) for px, py in p] for p in _geometry(geom)]))
    return out


# ---------------------------------------------------------------- render

def draw_map(cache, lat, lon, w_dp=TILE_DP[0], h_dp=TILE_DP[1], px_per_dp=OUT_SCALE, m_per_dp=M_PER_DP):
    """The map around (lat, lon), w_dp x h_dp at px_per_dp, as a PIL image (anti-aliased by supersampling)."""
    from PIL import Image, ImageChops, ImageDraw

    W, H = round(w_dp * px_per_dp), round(h_dp * px_per_dp)
    px_per_m = px_per_dp / m_per_dp * SS
    n = 2 ** Z
    # Web Mercator around the centre: metres per tile unit at this latitude
    m_per_tile = 40_075_016.686 * math.cos(math.radians(lat)) / n
    cx = (lon + 180) / 360 * n
    r = math.radians(lat)
    cy = (1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n

    def proj(tx, ty, ux, uy):
        return ((tx + ux - cx) * m_per_tile * px_per_m + W * SS / 2,
                (ty + uy - cy) * m_per_tile * px_per_m + H * SS / 2)

    water, roads, rails = [], [], []
    for tx, ty in tiles_for(lat, lon, w_dp * m_per_dp / 2 + 30, h_dp * m_per_dp / 2 + 30):
        with open(os.path.join(cache, f"{Z}_{tx}_{ty}.pbf"), "rb") as f:
            t = decode_tile(f.read(), {"water", "transportation"})
        for props, gtype, parts in t.get("water", []):
            if props.get("brunnel") == "tunnel" or gtype != 3:
                continue
            water.append([[proj(tx, ty, *p) for p in part] for part in parts])
        for props, gtype, parts in t.get("transportation", []):
            cls = props.get("class", "")
            # SBB draws rail tunnels too (Zürich HB's underground lines), but not road tunnels.
            if gtype != 2 or (props.get("brunnel") == "tunnel" and cls not in ("rail", "transit")):
                continue
            lines = [[proj(tx, ty, *p) for p in part] for part in parts]
            if cls in ("rail", "transit"):
                sub = props.get("subclass", "")
                if sub in ("rail", "narrow_gauge", "light_rail", "funicular", "preserved", ""):
                    rails.append((lines, W_RAIL, RAIL))
                elif sub == "tram":
                    roads.append((lines, W_TRAM, TRAM, 3))
            elif cls in ("motorway", "trunk", "primary", "secondary"):
                roads.append((lines, W_MAJOR, STREET_MAJOR, 2))
            elif cls in ("tertiary", "minor", "busway", "raceway"):
                roads.append((lines, W_STREET, STREET, 1))
            elif cls == "service":
                roads.append((lines, W_SERVICE, STREET, 1))
            elif cls in ("path", "track", "pedestrian"):
                roads.append((lines, W_PATH, PATH, 0))

    img = Image.new("RGB", (W * SS, H * SS), BG)
    d = ImageDraw.Draw(img)
    # Water: each polygon's rings combined even-odd (lakes with islands).
    for rings in water:
        rings = [r for r in rings if len(r) >= 3]
        if len(rings) == 1:
            d.polygon(rings[0], fill=WATER)
            continue
        mask = Image.new("1", img.size, 0)
        for ring in rings:
            if len(ring) >= 3:
                tmp = Image.new("1", img.size, 0)
                ImageDraw.Draw(tmp).polygon(ring, fill=1)
                mask = ImageChops.logical_xor(mask, tmp)
        img.paste(WATER, mask=mask)

    def stroke(lines, w_dp_, col):
        w = max(1, round(w_dp_ * px_per_dp * SS))
        for ln in lines:
            if len(ln) < 2:
                continue
            d.line(ln, fill=col, width=w, joint="curve")
            rad = w / 2  # round caps
            for x, y in (ln[0], ln[-1]):
                d.ellipse((x - rad, y - rad, x + rad, y + rad), fill=col)

    for rank in (0, 1, 2, 3):
        for lines, w, col, rk in roads:
            if rk == rank:
                stroke(lines, w, col)
    for lines, w, col in rails:
        stroke(lines, w, col)
    return img.resize((W, H), Image.LANCZOS)


def render(cache, outdir, only=None):
    os.makedirs(outdir, exist_ok=True)
    for sid, (lat, lon) in STATIONS.items():
        if only and sid not in only:
            continue
        img = draw_map(cache, lat, lon)
        img.save(os.path.join(outdir, f"{sid}.webp"), "WEBP", quality=88, method=6)
        print(sid, f"{img.width}x{img.height}", flush=True)


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "fetch":
        fetch(sys.argv[2])
    elif len(sys.argv) >= 4 and sys.argv[1] == "render":
        render(sys.argv[2], sys.argv[3], set(sys.argv[4:]) or None)
    else:
        sys.exit(__doc__)
