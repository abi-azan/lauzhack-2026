// Builds basemap.json, the offline background of the native app's map (ui/map): lakes, country and canton borders
// and place labels around Switzerland, from OpenFreeMap's z8 vector tiles (OpenMapTiles schema, OpenStreetMap data,
// ODbL). rail-network.json next to it comes from the web app (built from the same tiles by its map-rail-network.mjs).
// Run once, by hand; the build only copies the output into composeResources/files/map.
//   npm i @mapbox/vector-tile@2 pbf@4   (anywhere; pass that folder's node_modules via NODE_PATH)
//   NODE_PATH=/path/to/node_modules node build-basemap.mjs raw.json
//   python3 clean-basemap.py raw.json basemap.json     (shapely: merges tiles, drops ponds, simplifies)
import { createRequire } from 'module';
import { writeFileSync } from 'fs';
const require = createRequire(import.meta.url);
const { VectorTile } = require('@mapbox/vector-tile');
const Pbf = require('pbf').default ?? require('pbf');

const [out = 'raw.json'] = process.argv.slice(2);
const [W, S, E, N] = [5.0, 44.6, 11.6, 48.6];
const Z = 8; const NT = 2 ** Z;
const tx = (lon) => Math.floor((lon + 180) / 360 * NT);
const ty = (lat) => { const r = lat * Math.PI / 180; return Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * NT); };
const tmpl = (await (await fetch('https://tiles.openfreemap.org/planet')).json()).tiles[0];
const r4 = (v) => Math.round(v * 1e4) / 1e4;
const ring = (c) => c.map(([lo, la]) => [r4(lo), r4(la)]).filter((p, i, a) => i === 0 || p[0] !== a[i - 1][0] || p[1] !== a[i - 1][1]);

const water = []; const borders = []; const places = new Map();
for (let x = tx(W); x <= tx(E); x++) for (let y = ty(N); y <= ty(S); y++) {
  const buf = Buffer.from(await (await fetch(tmpl.replace('{z}', Z).replace('{x}', x).replace('{y}', y))).arrayBuffer());
  const t = new VectorTile(new Pbf(buf));
  const each = (name, f) => { const l = t.layers[name]; if (l) for (let i = 0; i < l.length; i++) f(l.feature(i)); };
  each('water', (f) => {
    if (f.properties.brunnel === 'tunnel') return;
    const g = f.toGeoJSON(x, y, Z).geometry;
    for (const poly of g.type === 'Polygon' ? [g.coordinates] : g.coordinates) {
      const rings = poly.map(ring).filter((r) => r.length >= 4);
      if (rings.length) water.push(rings);
    }
  });
  each('boundary', (f) => {
    const p = f.properties;
    if (p.maritime === 1 || p.disputed === 1 || (p.admin_level !== 2 && p.admin_level !== 4)) return;
    const g = f.toGeoJSON(x, y, Z).geometry;
    for (const l of g.type === 'LineString' ? [g.coordinates] : g.coordinates) {
      const r = ring(l); if (r.length >= 2) borders.push({ level: p.admin_level, line: r });
    }
  });
  each('place', (f) => {
    const p = f.properties;
    if (p.class !== 'city' && p.class !== 'town') return;
    const [lon, lat] = f.toGeoJSON(x, y, Z).geometry.coordinates;
    if (lon < W || lon > E || lat < S || lat > N) return;
    let name = p.name;
    if (name === 'Freiburg im Breisgau') name = 'Freiburg'; // as SBB shortens it
    const key = `${name}`;
    const prev = places.get(key);
    const rank = p.rank ?? 99;
    if (!prev || rank < prev.rank) places.set(key, { name, cls: p.class, rank, capital: p.capital ?? 99, lon: r4(lon), lat: r4(lat) });
  });
}

const features = [
  ...water.map((rings) => ({ type: 'Feature', properties: { layer: 'water' }, geometry: { type: 'Polygon', coordinates: rings } })),
  ...borders.map((b) => ({ type: 'Feature', properties: { layer: 'border', level: b.level }, geometry: { type: 'LineString', coordinates: b.line } })),
  ...[...places.values()].sort((a, b) => a.rank - b.rank).map((p) => ({
    type: 'Feature', properties: { layer: 'place', name: p.name, class: p.cls, rank: p.rank, capital: p.capital },
    geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
  })),
];
writeFileSync(out, JSON.stringify({ type: 'FeatureCollection', attribution: '© OpenMapTiles © OpenStreetMap contributors (ODbL), via OpenFreeMap', features }));
console.log(`water ${water.length} borders ${borders.length} places ${places.size}`);
