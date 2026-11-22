// Golden data for the Kotlin port of mobile/src/incident (engine.ts, personal.ts, live.ts, client.ts's fakeFacts).
// Runs the TypeScript on a broad set of inputs and prints what it answered, for the Kotlin tests to compare.
// It reads the deleted Node chat's eval and research set: first restore them with
//   git checkout 4a50767 -- sbb-prototype/chat     (and remove them again afterwards)
// From sbb-prototype/native/composeApp/src/commonTest/kotlin/railstatus/mobile/incident/golden (Node 22.18+):
//   TZ=Europe/Zurich node gen.ts ../../../../../../../../.. > /tmp/golden.json
//   python3 write_kt.py /tmp/golden.json ../GoldenData.kt ../../../../../../../../../kmp/shared/src/commonTest/kotlin/railstatus/chat/GoldenData.kt
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const root = path.resolve(process.argv[2]); // sbb-prototype/
// script.ts, personal.ts and live.ts import the browser-only data/clock; patched copies go here.
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'incident-golden-'));
const E = await import(path.join(root, 'mobile/src/incident/chat/engine.ts'));
const data = await import(path.join(root, 'chat/research/data.mjs'));
const { answer, norm, detectLang, classify, findStation, chatOpen, clockOf, addToClock, groundCheck, parseLlm, llmPrompt, clusterOf } = E;

// ---------- fixtures (shaped like the backend's JSON; alternatives always present, as the server sends them) ----------

const st = (id: string, name: string) => ({ id, name });
const evalTrain = {
  id: 'IR90-1734', product: 'IR', line: 'IR 90', number: '1734', direction: 'Brig',
  stops: [
    { station: st('8501026', 'Genève-Aéroport'), dep: '17:20' },
    { station: st('8501008', 'Genève'), arr: '17:27', dep: '17:30' },
    { station: st('8501030', 'Nyon'), arr: '17:43', dep: '17:44' },
    { station: st('8501037', 'Morges'), arr: '18:00', dep: '18:01' },
    { station: st('8501120', 'Lausanne'), arr: '18:10', dep: '18:13' },
    { station: st('8501300', 'Montreux'), arr: '18:33', dep: '18:34' },
    { station: st('8501506', 'Sion'), arr: '19:23', dep: '19:24' },
    { station: st('8501609', 'Brig'), arr: '20:12' },
  ],
};
// The fake train as client.ts's chatTrain() builds it, checked in at 2026-09-17T17:16 Europe/Zurich.
const CHECKED_IN = Date.parse('2026-09-17T15:16:00Z');
const OFFSETS: [string, number][] = [['Genève-Aéroport', -11], ['Genève', -5], ['Nyon', 12], ['Morges', 26], ['Lausanne', 36], ['Montreux', 58], ['Sion', 93], ['Visp', 122], ['Brig', 131]];
const hhmm = (d: Date) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
const fakeTrain = {
  id: 'IR90-1734', product: 'IR', line: 'IR 90', number: '1734', direction: 'Brig',
  stops: OFFSETS.map(([name, off]) => ({ station: { id: name, name }, arr: hhmm(new Date(CHECKED_IN + off * 60_000)) })),
};

const u = (id: string, seq: number, stage: string, en: string, publishedAt: string, nextUpdateBy?: string) =>
  ({ id, incidentId: 'inc-1', seq, stage, title: { en }, body: { en }, publishedAt, nextUpdateBy });
const early = {
  id: 'inc-1', version: 1, stage: 'investigating', trainIds: [evalTrain.id],
  section: { from: st('8501118', 'Renens VD'), to: st('8501035', 'Allaman') },
  alternatives: [] as any[],
  nextUpdateBy: '2026-09-17T15:55:00Z',
  updates: [u('u1', 1, 'investigating', 'Train stopped: we are finding out why', '2026-09-17T15:45:00Z', '2026-09-17T15:55:00Z')],
};
const identified = {
  ...early, version: 2, stage: 'identified',
  cause: { en: 'overhead line fault and power failure', de: 'Fahrleitungsstörung und Stromausfall', fr: 'dérangement de la ligne de contact et panne de courant', it: 'guasto alla linea di contatto e mancanza di corrente' },
  delayMin: { low: 25, high: 35 },
  nextUpdateBy: '2026-09-17T16:10:00Z',
  updates: [...early.updates, u('u2', 2, 'identified', 'Cause: overhead line fault', '2026-09-17T15:55:00Z', '2026-09-17T16:10:00Z')],
};
const withAlternatives = {
  ...identified, version: 3, stage: 'monitoring',
  alternatives: [
    { id: 'a1', text: { en: 'replacement buses run between Renens VD and Allaman.', de: 'Ersatzbusse fahren zwischen Renens VD und Allaman', fr: 'des bus de remplacement circulent entre Renens VD et Allaman', it: 'bus sostitutivi tra Renens VD e Allaman' } },
    { id: 'a2', text: { de: 'Nur deutsch.。' }, via: 'Morges' },
  ],
  onboard: { en: 'Water is being handed out in car 4', de: 'In Wagen 4 wird Wasser verteilt', fr: 'De l’eau est distribuée dans la voiture 4', it: 'Nella carrozza 4 viene distribuita acqua' },
  updates: [...identified.updates, u('u3', 3, 'monitoring', 'Replacement buses at 16:25, 40 min', '2026-09-17T16:10:00Z', '2026-09-17T16:30:00Z')],
  nextUpdateBy: '2026-09-17T16:30:00Z',
};
const resolved = {
  ...withAlternatives, version: 4, stage: 'resolved', delayMin: { low: 30, high: 30 }, nextUpdateBy: undefined,
  resolvedAt: '2026-09-17T16:40:00Z',
  updates: [...withAlternatives.updates, u('u4', 4, 'resolved', 'Resolved', '2026-09-17T16:40:00Z')],
};

// The scripted demo as client.ts's fakeFacts(incidentFrom(sent)) builds it. Copied from client.ts and store.ts.
// script.ts imports '../data/clock' (browser-only); swap in its addMin.
const scriptSrc = fs.readFileSync(path.join(root, 'mobile/src/incident/script.ts'), 'utf8')
  .replace(/import \{ addMin \} from '..\/data\/clock';/, 'const addMin = (d: Date, m: number) => new Date(d.getTime() + m * 60_000);')
  .replace(/import type .*\n/g, '');
fs.writeFileSync(path.join(tmp, 'script.ts'), scriptSrc);
const S = await import(path.join(tmp, 'script.ts'));
const clockShim = `const addMin = (d: Date, m: number) => new Date(d.getTime() + m * 60_000);
const hhmm = (d: Date) => \`\${String(d.getHours()).padStart(2, '0')}:\${String(d.getMinutes()).padStart(2, '0')}\`;
const now = () => new Date(Date.parse('2026-09-17T12:00:00Z'));`;
const personalSrc = fs.readFileSync(path.join(root, 'mobile/src/incident/personal.ts'), 'utf8')
  .replace(/import \{ addMin, hhmm \} from '..\/data\/clock';/, clockShim).replace(/import type .*\n/g, '');
fs.writeFileSync(path.join(tmp, 'personal.ts'), personalSrc);
const P = await import(path.join(tmp, 'personal.ts'));
const liveSrc = fs.readFileSync(path.join(root, 'mobile/src/incident/live.ts'), 'utf8')
  .replace(/import \{ now \} from '..\/data\/clock';/, clockShim).replace(/import type [^;]*;\n/g, '');
fs.writeFileSync(path.join(tmp, 'live.ts'), liveSrc);
const LV = await import(path.join(tmp, 'live.ts'));
const SENT = [0, 4, 9, 15, 31].map((m) => Date.parse('2026-09-17T15:20:00Z') + m * 60_000 + 1234);
function incidentFrom(sent: number[]) {
  const updates = sent.map((ms, index) => {
    const x = S.UPDATES[index];
    return { ...x, index, at: new Date(ms), nextBy: x.nextInMin ? new Date(ms + x.nextInMin * 60_000) : undefined };
  });
  const latest = updates[updates.length - 1];
  return { id: `renens-allaman-${sent[0]}`, trainId: S.TRAIN.id, section: S.SECTION, stage: latest.stage, updates, latest };
}
function fakeFacts(inc: any) {
  const latest = inc.latest;
  const known = inc.updates.reduce(
    (acc: any, x: any) => ({ cause: x.cause ?? acc.cause, alternatives: x.alternatives ?? acc.alternatives, onboard: x.onboard ?? acc.onboard }),
    {},
  );
  return {
    id: inc.id, version: inc.updates.length, stage: inc.stage, trainIds: [inc.trainId],
    ...known,
    delayMin: latest.delay,
    nextUpdateBy: latest.nextBy?.toISOString(),
    resolvedAt: inc.stage === 'resolved' ? latest.at.toISOString() : undefined,
    updates: inc.updates.map((x: any) => ({
      id: `u${x.index}`, seq: x.index, stage: x.stage, title: x.title, body: x.body,
      publishedAt: x.at.toISOString(), nextUpdateBy: x.nextBy?.toISOString(),
    })),
  };
}
const fake: Record<string, any> = {};
for (let k = 1; k <= 5; k++) fake[`fake${k}`] = fakeFacts(incidentFrom(SENT.slice(0, k)));
// fakeFacts leaves alternatives undefined until an update publishes them; the Kotlin model has [] instead,
// which the engine treats the same. Record both: the raw TS output (for the conversion test) and the
// engine input with [] (for the answers).
const fakeRaw = JSON.parse(JSON.stringify(fake));
for (const f of Object.values(fake)) { f.alternatives ??= []; for (const x of f.updates) x.incidentId = f.id; }

const FACTS: Record<string, any> = { early, identified, withAlternatives, resolved, ...fake };
const TRAINS: Record<string, any> = { eval: evalTrain, fake: fakeTrain };

// ---------- questions ----------

const evalSrc = fs.readFileSync(path.join(root, 'chat/eval.ts'), 'utf8');
const evalArr = evalSrc.slice(evalSrc.indexOf('const CASES: Case[] = [') + 'const CASES: Case[] = '.length, evalSrc.indexOf('];\n\nconst verbose') + 1);
const EVAL_CASES = new Function('early', 'identified', 'withAlternatives', `return ${evalArr}`)('early', 'identified', 'withAlternatives') as [string, string, string, string, string?][];

const ansSrc = fs.readFileSync(path.join(root, 'chat/research/answer.mjs'), 'utf8');
const ansArr = ansSrc.slice(ansSrc.indexOf('const CASES = [') + 'const CASES = '.length, ansSrc.indexOf('];', ansSrc.indexOf('const CASES = [')) + 1);
const ANSWER_QS = (new Function(`return ${ansArr}`)() as any[]).map((c) => c[2] as string);

const chatSrc = fs.readFileSync(path.join(root, 'mobile/src/incident/chat/ChatScreen.tsx'), 'utf8');
const sugArr = chatSrc.slice(chatSrc.indexOf('const SUGGESTIONS') ).replace(/^[^=]*=\s*/, '');
const SUGGESTIONS = new Function(`return ${sugArr.slice(0, sugArr.indexOf('};') + 1)}`)() as Record<string, string[]>;

const EXTRA = [
  '', '   ', '?!', 'HELP', 'help me', 'Hilfe!', 'SOS', 'au secours', 'a l’aide', 'Aiuto!!', 'help me please',
  'ok', 'ok thanks', 'Thank you so much for the help', 'thanks!', 'Danke vielmals', 'merci beaucoup', 'grazie mille', 'super',
  'hi', 'Hello there', 'Grüezi mitenand', 'Bonjour', 'Buongiorno a tutti', 'good morning', 'Hey, what happened?',
  'STRASSE ß Größe', 'Ça va? Où est-ce qu’on va?', 'Zürich HB connection?', 'connection in zurich', 'Anschluss in Zürich?',
  'Will I make my connection in Lausanne?', 'Will I make my connection in Atlantis?', 'Will I make my connection?',
  'Connection to Zermatt', 'connection in Car 5', 'Anschluss nach Zermatt?', 'correspondance à Zermatt ?', 'coincidenza per Zermatt?',
  'When do we arrive in Visp?', 'Are we late to Brig?', 'When do we arrive in Genève-Aéroport?', 'When do we arrive at the airport?',
  'When do we arrive in Geneva?', 'when do we get to Renens?', 'Wann kommen wir in Sitten an?', 'arrivo a Briga quando?',
  'Does this train stop in Bern?', 'Hält der Zug in Thun?', 'Is there a bus from Renens to Allaman?', 'bus to Fribourg?',
  'Someone is on the tracks', 'Was it a person on the tracks?', 'Jemand auf den Gleisen!', 'War es eine Person auf den Gleisen?',
  'quelqu’un sur les voies', 'c’était une personne sur les voies ?', 'qualcuno sui binari', 'è stato una persona sui binari?',
  'Is there a fire?', 'smoke in the toilet', 'Es riecht nach Rauch', 'odeur de fumée', 'puzza di bruciato',
  'Can I get off in Lausanne? When do we arrive?', 'I get off in Lausanne, when do we arrive?', 'Can I get off?', 'Darf ich aussteigen?',
  'peut-on descendre ?', 'posso scendere?', 'open the door please', 'jump off the train',
  'next update?', 'any news?', 'Neuigkeiten?', 'des nouvelles ?', 'novità?', 'refund please', 'Rückerstattung bitte',
  'is it too cold?', 'wifi?', 'Is there food?', 'Wasser bitte', 'de l’eau svp', 'acqua per favore',
  'I am pregnant and need to sit', 'Rollstuhl in Wagen 3', 'fauteuil roulant', 'sedia a rotelle',
  'Tell me a joke', 'What is the meaning of life?', 'Who are you?', 'Wer bist du?', 'Qui es-tu ?', 'Chi sei?',
  'my flight leaves GVA at 19:05', 'How late will we be in Montreux at 18:33?', 'how long to Lausanne', 'eta Sion',
  'Will the train start again soon?', 'are we stuck', 'Ist der Zug pünktlich?', 'à l’heure ?', 'in orario?',
  'Will I be in time to Zermatt?', 'Pour Zermatt ?', 'in Lausanne', 'nach Genf', 'Is the 18:10 to Visp waiting?',
  'Ich komme in Zermatt an?', 'connection in Atlantis', 'Connection in Éclépens?', 'connection in ÉCLÉPENS',
  'connection at Ölten', 'delay to Ölten?', 'Train to X-ray', 'alternative to Lausanne?', 'other way to Zermatt?',
  'connection in Ólten', '👋 hello', 'thanks 🙏', 'émergence', 'docteur?', 'medico!', 'Arzt bitte',
];

const seen = new Set<string>();
const QS: string[] = [];
const add = (q: string) => { if (!seen.has(q)) { seen.add(q); QS.push(q); } };
EVAL_CASES.forEach((c) => add(c[0]));
for (const topic of Object.values(data.exemplars) as any[]) for (const qs of Object.values(topic) as string[][]) qs.forEach(add);
(data.test as any[]).forEach((c) => add(c.q));
ANSWER_QS.forEach(add);
Object.values(SUGGESTIONS).flat().forEach(add);
EXTRA.forEach(add);

// ---------- cases ----------

const LANGS = ['en', 'de', 'fr', 'it'] as const;
const TOPICS = ['emergency', 'accessibility', 'leave_train', 'refund', 'connection', 'alternative', 'next_update', 'delay', 'cause', 'onboard', 'greeting', 'other'];
const DESTS = [undefined, 'Lausanne', '8501506', 'Genève-Aéroport', 'Sion', 'Bern', ''];

type In = { text: string; lang?: string; facts: string; train?: string; destination?: string; forwarded?: boolean; timeZone?: string; topic?: string; station?: any };
const cases: { q: In; reply: any; ground?: string[] }[] = [];
const run = (q: In, ground = false) => {
  const full = { ...q, facts: FACTS[q.facts], train: q.train ? TRAINS[q.train] : undefined };
  const reply = answer(full);
  cases.push({ q, reply, ...(ground ? { ground: groundCheck(reply.text, full) } : {}) });
};

QS.forEach((text, i) => {
  for (const facts of ['early', 'identified', 'withAlternatives', 'resolved']) {
    run({ text, lang: 'en', facts, train: 'eval', destination: '8501506', timeZone: 'Europe/Zurich' }, true);
  }
  run({
    text, lang: LANGS[i % 4], facts: `fake${(i % 5) + 1}`, train: 'fake', destination: DESTS[i % DESTS.length],
    forwarded: false, timeZone: i % 3 === 0 ? 'UTC' : i % 3 === 1 ? undefined : 'America/New_York',
  });
  run({ text, lang: LANGS[(i + 1) % 4], facts: i % 2 ? 'identified' : 'early', destination: i % 2 ? 'Lausanne' : undefined, forwarded: true, timeZone: 'Not/AZone' });
});
// The chat service's classifier path: a topic and station decided elsewhere.
const OVERRIDE_QS = ['what about it?', 'GVA?', 'Will I make it in Lausanne?', 'hello', 'help', 'Zermatt'];
OVERRIDE_QS.forEach((text, i) => {
  for (const topic of TOPICS) {
    for (const station of [undefined, { id: '8501120', name: 'Lausanne' }, { name: 'Zermatt' }, { id: '8501026', name: 'Genève-Aéroport' }]) {
      run({ text, lang: LANGS[i % 4], facts: i % 2 ? 'withAlternatives' : 'early', train: 'eval', destination: 'Sion', topic, station });
    }
  }
});

// ---------- helpers ----------

const helpers = QS.map((text) => {
  const n = norm(text);
  return {
    text, n,
    langEn: detectLang(text, 'en'), langIt: detectLang(text, 'it'), langDe: detectLang(text),
    topic: classify(n), topicAt: classify(n, { name: 'X' }),
    station: findStation(n, evalTrain, identified) ?? null,
    stationNoTrain: findStation(n) ?? null,
  };
});

const CLOCKS: [string, string | undefined][] = [
  ['2026-09-17T15:55:00Z', undefined], ['2026-09-17T15:55:00Z', 'UTC'], ['2026-09-17T15:55:00Z', 'Europe/Zurich'],
  ['2026-09-17T23:59:59.999Z', 'Europe/Zurich'], ['2026-12-31T23:30:00+01:00', 'America/New_York'], ['2026-03-29T01:30:00Z', 'Europe/Zurich'],
  ['2026-09-17T15:55:00.000Z', 'Asia/Kolkata'], ['2026-09-17T15:55:00Z', 'Not/AZone'], ['garbage', 'UTC'], ['', 'UTC'], ['17:36', undefined],
  ['2026-09-17', 'Europe/Zurich'], ['2026-09-17T15:55', 'UTC'], ['2026-09-17T15:55:07+02:00', 'Europe/Zurich'],
];
const clocks = CLOCKS.map(([iso, tz]) => ({ iso, tz, out: clockOf(iso, tz) }));

const ADDS: [string, number][] = [['14:04', 27], ['23:50', 25], ['00:10', -30], ['7:5', 0], ['18:10', 1440], ['18:10', -2000], ['', 5], ['ab:cd', 61], ['14:04:59', 1], ['24:00', 0]];
const adds = ADDS.map(([hhmm, min]) => ({ hhmm, min, out: addToClock(hhmm, min) }));

const OPEN_AT = ['2026-09-17T15:00:00Z', '2026-09-17T16:41:00Z', '2026-09-17T16:54:59Z', '2026-09-17T16:55:00Z', '2026-09-17T18:00:00Z'];
const opens: any[] = [];
for (const [key, f] of Object.entries(FACTS)) for (const at of OPEN_AT) opens.push({ facts: key, now: Date.parse(at), out: chatOpen(f, new Date(at)) });
opens.push({ facts: null, now: 0, out: chatOpen(undefined, new Date(0)) });
const detected = { ...early, stage: 'detected', updates: [] };
const noResolvedAt = { ...resolved, resolvedAt: undefined };
const badResolvedAt = { ...resolved, resolvedAt: 'nope' };
const onlyDetected = { ...early, updates: [{ ...early.updates[0], stage: 'detected' }] };
FACTS.detected = detected; FACTS.noResolvedAt = noResolvedAt; FACTS.badResolvedAt = badResolvedAt; FACTS.onlyDetected = onlyDetected;
for (const key of ['detected', 'noResolvedAt', 'badResolvedAt', 'onlyDetected']) for (const at of OPEN_AT) opens.push({ facts: key, now: Date.parse(at), out: chatOpen(FACTS[key], new Date(at)) });

const LLM_RAW = [
  '{"answer":"The cause is an overhead line fault.","cited_update_ids":["u2"],"known":true}',
  'Sure! ```json\n{"answer":" Ok. ","cited_update_ids":[],"known":true}\n``` done',
  '{"answer":"x","cited_update_ids":["u9"],"known":true}',
  '{"answer":"x","cited_update_ids":["u1"],"known":false}',
  '{"answer":"x","known":"true"}',
  '{"answer":"   ","known":true}',
  '{"answer":5,"known":true}',
  'not json',
  '{"answer":"a"} and {"b":1}',
  '{"answer":"no cites","known":true}',
  '{"answer":"cites not array","cited_update_ids":"u1","known":true}',
  'null',
  '[1,2]',
  '{"answer":"nums","cited_update_ids":[1],"known":true}',
];
const llm = LLM_RAW.map((raw) => ({ raw, out: parseLlm(raw, withAlternatives) ?? null }));

const prompts: any[] = [];
QS.slice(0, 8).forEach((text, i) => {
  for (const facts of ['early', 'withAlternatives', 'resolved', 'fake3']) {
    const q = { text, facts: FACTS[facts], train: i % 2 ? evalTrain : undefined, timeZone: i % 3 ? 'Europe/Zurich' : undefined };
    prompts.push({ text, facts, train: i % 2 ? 'eval' : undefined, timeZone: q.timeZone, lang: LANGS[i % 4], out: llmPrompt(q, LANGS[i % 4]) });
  }
});

const GROUND_TEXTS = [
  'The train arrives at 18:35, about 25 min late.', 'Buses at 16.25 from Morges', 'Next update by 18:30 in Bern.',
  'Delay of 40 minutes', 'Car 4 has water', 'Geneva and Lausanne', 'Ersatzbusse ab Renens VD um 7h05', 'Prochaine info à 18h10',
];
const grounds: any[] = [];
for (const text of GROUND_TEXTS) for (const facts of ['early', 'withAlternatives']) for (const train of ['eval', undefined]) {
  grounds.push({ text, facts, train, timeZone: 'Europe/Zurich', out: groundCheck(text, { text: '', facts: FACTS[facts], train: train ? TRAINS[train] : undefined, timeZone: 'Europe/Zurich' }) });
}

const clusters = [
  ['connection', { id: '8501120', name: 'Lausanne' }], ['connection', { name: 'Genève-Aéroport' }], ['delay', { name: 'Zürich HB' }],
  ['alternative', { id: '', name: 'X' }], ['cause', { name: 'Lausanne' }], ['greeting', undefined], ['other', undefined],
].map(([t, s]) => ({ topic: t, station: s, out: clusterOf(t, s) ?? null }));

const evalCases = EVAL_CASES.map(([text, topic, lang, facts, source]) => ({ text, topic, lang, facts, source }));

// ---------- personal.ts ----------
const fakeTrainView = { ...S.TRAIN, stops: S.TRAIN.stops.map((x: any) => ({ station: x.station, planned: new Date(CHECKED_IN + x.offsetMin * 60_000) })) };
const personal: any[] = [];
for (const pid of P.PERSONA_IDS) for (const asked of [undefined, 'Lausanne', 'Sion', 'Brig', 'Genève-Aéroport', 'Bern', '']) for (let k = 1; k <= 5; k++) {
  const persona = P.PERSONAS[pid];
  const dest = P.destinationOf(persona, asked);
  const inc = incidentFrom(SENT.slice(0, k));
  personal.push({ persona: pid, asked, k, dest: dest ?? null, out: P.forYou(inc.latest, persona, dest, fakeTrainView) });
}
const names: any[] = [];
for (const st of ['Genève-Aéroport', 'Genève', 'Lausanne', 'Sion', 'Bern', 'Thun', 'Visp', 'Brig', 'Nyon', '']) for (const l of LANGS) names.push({ st, l, out: P.stationName(st, l) });

// ---------- live.ts mapping ----------
const partial = { ...withAlternatives, trainIds: [], section: undefined, delayMin: { low: 5, high: 10 },
  updates: [{ ...withAlternatives.updates[0], title: { de: 'Nur DE' }, body: {} }, { ...withAlternatives.updates[1], stage: 'detected' }, { ...withAlternatives.updates[2], nextUpdateBy: '' }] };
const MAP_FACTS: Record<string, any> = { early, identified, withAlternatives, resolved, detected, onlyDetected, partial };
const mapped: any[] = Object.entries(MAP_FACTS).map(([key, f]) => ({ key, facts: f, out: LV.mapIncident(f) ?? null }));
const MAP_TRAINS = [
  evalTrain,
  { id: 'IC1-725', product: 'IC', line: 'IC1', number: '725', direction: 'St. Gallen', stops: [{ station: st('1', 'A'), dep: '07:02' }, { station: st('2', 'B'), arr: '25:70' }, { station: st('3', 'C') }, { station: st('4', 'D'), arr: '', dep: '9:5' }] },
  { id: 'S3-1', product: 'S', line: 'S 3', number: '1', direction: 'X', stops: [{ station: st('1', 'A'), arr: '23:59' }] },
  { id: 'RE33', product: 'RE', line: 'Regio 33', number: '4234', direction: 'Y', stops: [{ station: st('1', 'A'), dep: '02:30' }] },
];
const mappedTrains = MAP_TRAINS.map((t) => ({ train: t, out: LV.mapTrain(t) }));

process.stdout.write(JSON.stringify({
  personal, names, mapped, mappedTrains, mapNow: Date.parse('2026-09-17T12:00:00Z'),
  facts: FACTS, trains: TRAINS, fakeRaw, sent: SENT, checkedIn: CHECKED_IN,
  cases, helpers, clocks, adds, opens, llm, prompts, grounds, clusters, evalCases,
}));
