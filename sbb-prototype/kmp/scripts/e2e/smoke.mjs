#!/usr/bin/env node
// End-to-end smoke test for the 9 demo steps (task T10), headless, against a running backend.
//
//   node scripts/e2e/smoke.mjs https://<tunnel>.trycloudflare.com      # or E2E_BASE=..., default http://localhost:8080
//
// Flags:
//   --reset       POST /api/control/reset first. This wipes the live demo state for everyone.
//   --force       run even if other passengers are checked in to the demo train (they would get the test updates)
//   --no-wait     skip waiting for the 60 s safety net (step 3 is reported as SKIP)
//   --no-ai       use a template instead of the AI draft in step 4
//   --train=ID    default IR90-1734
//
// It plays the phone with a fake passenger on /ws/passenger and the laptop with REST calls plus /ws/admin.
// Instead of the Python replayer (which needs Redis on the server's machine) it sends the 17 Sep 2026
// Renens VD – Allaman fault through POST /api/ingest/ticker, under a fresh sourceEventId per run.
// Every published title is prefixed "[E2E TEST]", and the incident is resolved at the end.
// Node 22+ (global fetch and WebSocket), no dependencies. Exit code 0 = every step passed or was skipped.

const args = process.argv.slice(2);
const flag = (f) => args.includes(f);
const opt = (name, dflt) => args.find((a) => a.startsWith(`--${name}=`))?.split('=')[1] ?? dflt;
const BASE = (args.find((a) => !a.startsWith('--')) ?? process.env.E2E_BASE ?? 'http://localhost:8080').replace(/\/+$/, '');
const WS_BASE = BASE.replace(/^http/, 'ws');
const TRAIN = opt('train', 'IR90-1734');
const SAFETY_NET_TIMEOUT_MS = Number(process.env.E2E_SAFETY_NET_TIMEOUT_MS ?? 90_000);
const EVENT_TIMEOUT_MS = Number(process.env.E2E_EVENT_TIMEOUT_MS ?? 15_000);
// After --reset, real phones with the Kotlin app check themselves back in within a few seconds. Wait for them before
// counting, so the "other passengers" guard in step 1 sees them (otherwise they silently get every test update).
const REJOIN_WAIT_MS = Number(process.env.E2E_REJOIN_WAIT_MS ?? 8_000);
const TAG = '[E2E TEST] ';

const st = (id, name) => ({ id, name });
const LAUSANNE = st('8501120', 'Lausanne');
const GENEVE = st('8501008', 'Genève');
const RENENS = st('8501118', 'Renens VD');
const ALLAMAN = st('8501035', 'Allaman');

// ---------- small helpers ----------

class StepFail extends Error {}
const fail = (msg) => { throw new StepFail(msg); };

async function api(method, path, body, { ok = [200, 201, 202, 204] } = {}) {
  const res = await fetch(BASE + path, {
    method,
    // ADMIN_KEY (R3): the server may require it on admin, control and ingest routes.
    headers: { ...(body ? { 'content-type': 'application/json' } : {}), ...(process.env.ADMIN_KEY ? { 'X-Admin-Key': process.env.ADMIN_KEY } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(20_000),
  });
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : undefined; } catch { json = undefined; }
  if (!ok.includes(res.status)) fail(`${method} ${path} → HTTP ${res.status}: ${text.slice(0, 200)}`);
  return { status: res.status, json };
}

/** A WebSocket that buffers every JSON event, so a step can wait for one that may already have arrived. */
function socket(path) {
  const ws = new WebSocket(WS_BASE + path);
  const events = [];
  const waiters = new Set();
  let closed = null;
  ws.addEventListener('message', (m) => {
    let ev;
    try { ev = JSON.parse(typeof m.data === 'string' ? m.data : ''); } catch { return; }
    ev._at = Date.now();
    events.push(ev);
    for (const w of waiters) w();
  });
  ws.addEventListener('close', (e) => { closed = `${e.code} ${e.reason}`; for (const w of waiters) w(); });
  const opened = new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', () => reject(new StepFail(`WebSocket ${path.split('?')[0]} failed to open`)), { once: true });
  });
  /** Resolves with the first buffered event matching `pred` at index >= `from`. */
  function waitFor(pred, timeoutMs, what, from = 0) {
    return new Promise((resolve, reject) => {
      const check = () => {
        const hit = events.slice(from).find(pred);
        if (hit) { done(); resolve(hit); return true; }
        if (closed) { done(); reject(new StepFail(`socket closed (${closed}) while waiting for ${what}`)); return true; }
        return false;
      };
      const timer = setTimeout(() => { done(); reject(new StepFail(`no ${what} within ${Math.round(timeoutMs / 1000)} s`)); }, timeoutMs);
      const done = () => { clearTimeout(timer); waiters.delete(check); };
      if (!check()) waiters.add(check);
    });
  }
  return { ws, events, opened, waitFor, close: () => { try { ws.close(); } catch {} } };
}

const tagText = (t) => Object.fromEntries(Object.entries(t ?? {}).map(([k, v]) => [k, v.startsWith(TAG) ? v : TAG + v]));
const en = (t) => t?.en ?? Object.values(t ?? {})[0] ?? '';

// ---------- the run ----------

const results = [];
const ctx = {};

async function step(n, name, fn, { skip } = {}) {
  const t0 = Date.now();
  if (skip) {
    results.push({ n, name, status: 'SKIP', ms: 0, note: skip });
    console.log(`  ${String(n).padEnd(2)} SKIP  ${name}: ${skip}`);
    return;
  }
  try {
    const note = (await fn()) ?? '';
    const ms = Date.now() - t0;
    results.push({ n, name, status: 'PASS', ms, note });
    console.log(`  ${String(n).padEnd(2)} PASS  ${name} (${(ms / 1000).toFixed(1)} s)${note ? `: ${note}` : ''}`);
  } catch (e) {
    const ms = Date.now() - t0;
    const note = e instanceof StepFail ? e.message : `${e?.name ?? 'Error'}: ${e?.message ?? e}`;
    results.push({ n, name, status: 'FAIL', ms, note });
    console.log(`  ${String(n).padEnd(2)} FAIL  ${name} (${(ms / 1000).toFixed(1)} s): ${note}`);
    throw new StepFail('stop');
  }
}

async function main() {
  console.log(`e2e smoke test → ${BASE}  (train ${TRAIN})\n`);

  try {
    await step(0, 'backend reachable', async () => {
      const res = await fetch(BASE + '/health', { signal: AbortSignal.timeout(10_000) }).catch((e) => fail(`unreachable: ${e.message}`));
      if (!res.ok) fail(`/health → HTTP ${res.status}`);
      if (flag('--reset')) {
        await api('POST', '/api/control/reset');
        if (!flag('--force')) await new Promise((r) => setTimeout(r, REJOIN_WAIT_MS));
        return `state reset (--reset)${flag('--force') ? '' : `, waited ${REJOIN_WAIT_MS / 1000} s for phones to check back in`}`;
      }
    });

    ctx.admin = socket('/ws/admin' + (process.env.ADMIN_KEY ? '?key=' + encodeURIComponent(process.env.ADMIN_KEY) : ''));
    await ctx.admin.opened;

    await step(1, 'phone checks in to the train', async () => {
      const { json } = await api('POST', '/api/checkins', { trainId: TRAIN, lang: 'en', method: 'qr', destinationId: LAUSANNE.id });
      ctx.token = json.checkIn.token;
      ctx.passengerId = json.checkIn.passengerId;
      const counted = await ctx.admin.waitFor((e) => e.type === 'checkins.changed' && e.trainId === TRAIN, EVENT_TIMEOUT_MS, 'checkins.changed');
      if (counted.count > 1 && !flag('--force')) {
        fail(`${counted.count - 1} other passenger(s) are checked in to ${TRAIN} (real phones check back in by themselves after a reset); they would get the test updates. Check the demo phones out first, or re-run with --force if they should get them (e.g. the pre-stage phone check).`);
      }
      ctx.phone = socket(`/ws/passenger?token=${encodeURIComponent(ctx.token)}`);
      await ctx.phone.opened;
      const hello = await ctx.phone.waitFor((e) => e.type === 'hello', EVENT_TIMEOUT_MS, 'hello on /ws/passenger');
      if (hello.checkIn?.trainId !== TRAIN) fail(`hello is for ${hello.checkIn?.trainId}`);
      return `token …${ctx.token.slice(-6)}, ${counted.count} on board`;
    });

    await step(2, 'ticker reports the fault, an incident opens', async () => {
      ctx.sourceEventId = `e2e-${Date.now()}`;
      await api('POST', '/api/ingest/ticker', {
        sourceEventId: ctx.sourceEventId, kind: 'created', source: 'via', reasonCode: 'EG_E2E',
        line: { from: LAUSANNE, to: GENEVE }, location: { from: RENENS, to: ALLAMAN },
        text: `${TAG}contact line down Renens VD – Allaman (17 Sep 2026 replay)`,
      });
      const changed = await ctx.admin.waitFor(
        (e) => e.type === 'incident.changed' && e.incident?.sourceEventId === ctx.sourceEventId, EVENT_TIMEOUT_MS, 'incident.changed for the new incident');
      ctx.incidentId = changed.incident.facts.id;
      ctx.detectedAt = Date.now();
      const trains = changed.incident.facts.trainIds;
      if (!trains.includes(TRAIN)) fail(`incident ${ctx.incidentId} doesn't cover ${TRAIN} (trains: ${trains.join(', ')})`);
      return `${ctx.incidentId} on ${trains.join(', ')}`;
    });

    const isOurs = (e) => e.type === 'incident.update' && e.incident?.id === ctx.incidentId;

    await step(3, 'phone buzzes from the safety net', async () => {
      const ev = await ctx.phone.waitFor((e) => isOurs(e) && e.update?.author === 'auto', SAFETY_NET_TIMEOUT_MS, 'auto update (safety net)');
      ctx.seen = ctx.phone.events.indexOf(ev) + 1;
      return `"${en(ev.update.title)}" after ${((ev._at - ctx.detectedAt) / 1000).toFixed(0)} s`;
    }, { skip: flag('--no-wait') ? '--no-wait' : undefined });

    await step(4, 'admin drafts (AI) and publishes', async () => {
      let draft;
      let source = 'AI';
      if (!flag('--no-ai')) {
        const r = await api('POST', `/api/admin/incidents/${ctx.incidentId}/draft/ai`, undefined, { ok: [200, 501, 502, 500] });
        if (r.status === 200) draft = r.json;
        else source = `template (AI draft → HTTP ${r.status})`;
      }
      if (!draft) {
        if (flag('--no-ai')) source = 'template';
        const { json: templates } = await api('GET', '/api/templates');
        const tpl = templates.find((t) => t.stage === 'identified') ?? templates.find((t) => t.stage !== 'resolved');
        draft = (await api('POST', `/api/admin/incidents/${ctx.incidentId}/draft`, { templateId: tpl.id })).json;
      }
      const body = {
        ...draft,
        stage: draft.stage === 'investigating' || draft.stage === 'detected' ? 'identified' : draft.stage,
        title: tagText(draft.title),
        alternatives: draft.alternatives?.length ? draft.alternatives : [{
          id: 'e2e-bus', via: 'Allaman',
          text: { en: 'Replacement buses Allaman → Renens VD, from about 19:30', de: 'Ersatzbusse Allaman → Renens VD, ab ca. 19:30',
                  fr: 'Bus de remplacement Allaman → Renens VD, dès 19h30 env.', it: 'Bus sostitutivi Allaman → Renens VD, dalle 19:30 circa' },
        }],
        nextUpdateInMin: draft.nextUpdateInMin ?? 15,
      };
      ctx.mark = ctx.phone.events.length;
      const { json: update } = await api('POST', `/api/admin/incidents/${ctx.incidentId}/updates`, body);
      ctx.publishedSeq = update.seq;
      return `${source}, update ${update.seq} "${en(update.title)}"`;
    });

    await step(5, 'phone updates and shows the next move', async () => {
      const ev = await ctx.phone.waitFor((e) => isOurs(e) && e.update?.seq === ctx.publishedSeq, EVENT_TIMEOUT_MS, `update ${ctx.publishedSeq} on the phone`, ctx.mark);
      if (!ev.nextMove) fail(`update ${ctx.publishedSeq} arrived, but without nextMove`);
      return `${ev.nextMove.decision}: "${en(ev.nextMove.headline) || ev.nextMove.headline}"`;
    });

    await step(6, 'passenger asks the chat', async () => {
      ctx.question = 'Will I make my connection in Lausanne?';
      ctx.adminMark = ctx.admin.events.length;
      const { json } = await api('POST', `/api/chat/${encodeURIComponent(ctx.token)}`, { text: ctx.question, lang: 'en' });
      const reply = json?.reply;
      if (!reply?.text) fail(`no reply text: ${JSON.stringify(json).slice(0, 200)}`);
      ctx.clusterId = json.clusterId;
      return `${reply.topic}/${reply.source}: "${reply.text.slice(0, 90)}${reply.text.length > 90 ? '…' : ''}"`;
    });

    await step(7, "question reaches the admin's questions page", async () => {
      const ev = await ctx.admin.waitFor(
        (e) => (e.type === 'question.new' && e.incidentId === ctx.incidentId) || (e.type === 'questions.changed' && e.incidentId === ctx.incidentId),
        EVENT_TIMEOUT_MS, 'question.new / questions.changed on /ws/admin', ctx.adminMark);
      const { json: all } = await api('GET', '/api/admin/questions');
      const mine = all.find((q) => q.incidentId === ctx.incidentId);
      if (!mine?.clusters?.length) fail(`GET /api/admin/questions has no cluster for ${ctx.incidentId}`);
      return `${ev.type}; ${mine.clusters.length} group(s), top "${mine.clusters[0].label}" ×${mine.clusters[0].count}`;
    });

    await step(8, 'the next update answers it', async () => {
      ctx.mark = ctx.phone.events.length;
      const { json: update } = await api('POST', `/api/admin/incidents/${ctx.incidentId}/updates`, {
        stage: 'monitoring',
        title: { en: `${TAG}Replacement buses running` },
        body: { en: 'Replacement buses run between Allaman and Renens VD. From Renens VD, trains run to Lausanne as scheduled, so connections in Lausanne are reached about 60–90 min later.' },
        delayMin: { low: 60, high: 90 },
        nextUpdateInMin: 20,
      });
      const ev = await ctx.phone.waitFor((e) => isOurs(e) && e.update?.seq === update.seq, EVENT_TIMEOUT_MS, `update ${update.seq} on the phone`, ctx.mark);
      return `update ${ev.update.seq} (${ev.update.stage}) delivered${ev.nextMove ? `, next move ${ev.nextMove.decision}` : ''}`;
    });

    await step(9, 'resolved', async () => {
      ctx.mark = ctx.phone.events.length;
      const { json: update } = await api('POST', `/api/admin/incidents/${ctx.incidentId}/updates`, {
        stage: 'resolved', title: { en: `${TAG}Line reopened` }, body: { en: 'Trains run normally again between Renens VD and Allaman.' },
      });
      await ctx.phone.waitFor((e) => isOurs(e) && e.update?.seq === update.seq && e.update?.stage === 'resolved', EVENT_TIMEOUT_MS, 'resolved update on the phone', ctx.mark);
      const { json: pub } = await api('GET', `/api/incidents/${ctx.incidentId}`);
      if (pub.stage !== 'resolved') fail(`public status page says ${pub.stage}`);
      ctx.resolved = true;
      return 'phone and public status page both say resolved';
    });
  } catch (e) {
    if (!(e instanceof StepFail)) console.error(e);
  } finally {
    await cleanup();
  }

  const failed = results.filter((r) => r.status === 'FAIL');
  const passed = results.filter((r) => r.status === 'PASS' && r.n > 0).length;
  const reached = Math.max(...results.map((r) => r.n));
  console.log(`\n${failed.length ? 'FAILED' : 'OK'}: ${passed} of 9 demo steps passed${reached < 9 ? `, stopped at step ${reached}` : ''}.`);
  process.exit(failed.length ? 1 : 0);
}

async function cleanup() {
  // Leave nothing half-open on the live server: resolve our incident if a step failed after it opened, then check out.
  if (ctx.incidentId && !ctx.resolved) {
    await api('POST', `/api/admin/incidents/${ctx.incidentId}/updates`, {
      stage: 'resolved', title: { en: `${TAG}Test ended` }, body: { en: 'This was an automated test. Please ignore it.' },
    }).then(() => console.log(`  (cleanup: resolved ${ctx.incidentId})`)).catch((e) => console.log(`  (cleanup: couldn't resolve ${ctx.incidentId}: ${e.message})`));
  }
  if (ctx.token) await api('DELETE', `/api/checkins/${encodeURIComponent(ctx.token)}`).catch(() => {});
  ctx.phone?.close();
  ctx.admin?.close();
}

main();
