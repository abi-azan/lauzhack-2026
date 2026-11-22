"""Second step of build-basemap.mjs: merges the per-tile pieces, drops small lakes, simplifies, rounds.

Usage: python3 clean-basemap.py raw.json basemap.json
"""
import json
import sys

from shapely.geometry import box, mapping, shape
from shapely.ops import linemerge, unary_union

src, dst = sys.argv[1], sys.argv[2]
raw = json.load(open(src))
BBOX = box(5.0, 44.6, 11.6, 48.6)
MIN_LAKE = 4e-4  # deg², about 3 km² here: Murtensee and Sihlsee stay, ponds go
TOL = 0.0015  # deg, about 120 m


def rnd(coords):
    out = []
    for x, y in coords:
        p = [round(x, 4), round(y, 4)]
        if not out or out[-1] != p:
            out.append(p)
    return out


water = unary_union([shape(f["geometry"]).buffer(0) for f in raw["features"] if f["properties"]["layer"] == "water"])
water = water.intersection(BBOX)
polys = [g for g in getattr(water, "geoms", [water]) if g.geom_type == "Polygon" and g.area >= MIN_LAKE]
features = []
for g in polys:
    g = g.simplify(TOL, preserve_topology=True)
    if g.is_empty:
        continue
    for part in getattr(g, "geoms", [g]):
        rings = [rnd(part.exterior.coords)] + [rnd(r.coords) for r in part.interiors if r.length > 0.02]
        features.append({"type": "Feature", "properties": {"layer": "water"}, "geometry": {"type": "Polygon", "coordinates": rings}})

for level in (2, 4):
    lines = [shape(f["geometry"]) for f in raw["features"] if f["properties"]["layer"] == "border" and f["properties"]["level"] == level]
    merged = linemerge(unary_union(lines)).intersection(BBOX).simplify(TOL)
    for l in getattr(merged, "geoms", [merged]):
        if l.geom_type != "LineString" or l.length < 0.01:
            continue
        features.append({"type": "Feature", "properties": {"layer": "border", "level": level}, "geometry": {"type": "LineString", "coordinates": rnd(l.coords)}})

features += [f for f in raw["features"] if f["properties"]["layer"] == "place"]
json.dump({"type": "FeatureCollection", "attribution": raw["attribution"], "features": features}, open(dst, "w"), ensure_ascii=False, separators=(",", ":"))
print(len(polys), "lakes;", sum(1 for f in features if f["properties"]["layer"] == "border"), "border lines;",
      sum(1 for f in features if f["properties"]["layer"] == "place"), "places")
