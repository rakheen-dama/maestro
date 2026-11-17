// Scenario 4 (s4-signals): sends W x M signals at stepped arrival rates
// (open model, ramping-arrival-rate): each level L (signals/s) of PERF_LEVELS
// is held for PERF_HOLD_SECS seconds, with a 1 s step between levels.
// Iteration i signals workflow "${PERF_ID_PREFIX}${i mod W}" with the name
// "perf.s${floor(i / W)}" (no payload), so every workflow gets perf.s0 before
// any gets perf.s1, and each delivery is a distinct (workflowId, signalName)
// key. Iterations at or beyond W x M send nothing: exactly W x M signals.
//
// Routing (PERF_ROUTE): "own" sends the signal for workflow n to nodeFor(n),
// the node start-rate.js started it on (a run executes where it started);
// "next" sends it to nodeFor(n + 1), never the owner when there are two or
// more nodes: the signal row commits on a non-owner and the owner's parked
// await must be woken across nodes.
//
// Every request is tagged {name: "signal", level: "<L>"} (the stage the
// iteration started in), so the summary carries per-level counts.
// Env: PERF_NODES, PERF_PHASE (names the summary and plan files),
// PERF_ID_PREFIX, PERF_W, PERF_M, PERF_LEVELS ("50,100"), PERF_HOLD_SECS
// (30), PERF_ROUTE (own | next), PERF_K6_VUS / PERF_K6_MAX_VUS (50 / 300).
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { nodeFor, nodes, signalWorkflow, summary } from './lib/common.js';

function intEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const v = parseInt(raw, 10);
  if (!Number.isFinite(v) || v < 1 || String(v) !== raw.trim()) {
    throw new Error(`${name} must be a positive integer, got '${raw}'`);
  }
  return v;
}

const W = intEnv('PERF_W', 1);
const M = intEnv('PERF_M', 1);
const HOLD_SECS = intEnv('PERF_HOLD_SECS', 30);
const LEVELS = (__ENV.PERF_LEVELS || '50,100').split(',').map((s) => {
  const v = parseInt(s.trim(), 10);
  if (!Number.isFinite(v) || v < 1) {
    throw new Error(`PERF_LEVELS must be positive integers, got '${__ENV.PERF_LEVELS}'`);
  }
  return v;
});
const ROUTE = __ENV.PERF_ROUTE || 'own';
if (ROUTE !== 'own' && ROUTE !== 'next') {
  throw new Error(`PERF_ROUTE must be own or next, got '${ROUTE}'`);
}
const PRE_ALLOCATED_VUS = intEnv('PERF_K6_VUS', 50);
const MAX_VUS = Math.max(PRE_ALLOCATED_VUS, intEnv('PERF_K6_MAX_VUS', 300));
const PHASE = __ENV.PERF_PHASE || 'signal-rate';
const PREFIX = __ENV.PERF_ID_PREFIX || '';
const TOTAL = W * M;

const STAGES = [];
const BOUNDS = [];
{
  let t = 0;
  LEVELS.forEach((level, k) => {
    if (k > 0) {
      STAGES.push({ target: level, duration: '1s' });
    }
    STAGES.push({ target: level, duration: `${HOLD_SECS}s` });
    const to = t + ((k > 0 ? 1 : 0) + HOLD_SECS) * 1000;
    BOUNDS.push({ level: level, toMs: to });
    t = to;
  });
}

const SIGNALS_SENT = new Counter('signals_sent');

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    signals: {
      executor: 'ramping-arrival-rate',
      startRate: LEVELS[0],
      timeUnit: '1s',
      stages: STAGES,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  // Advisory only (D4): a crossed threshold exits 99, which the harness records.
  thresholds: {
    'http_req_failed{name:signal}': ['rate<0.01'],
    'http_req_duration{name:signal}': ['p(95)<2000'],
  },
};

function levelAt(ms) {
  for (const b of BOUNDS) {
    if (ms < b.toMs) {
      return b.level;
    }
  }
  return BOUNDS[BOUNDS.length - 1].level;
}

export default function () {
  if (PREFIX === '') {
    throw new Error('PERF_ID_PREFIX is required');
  }
  const i = exec.scenario.iterationInTest;
  if (i >= TOTAL) {
    return;
  }
  const n = i % W;
  const k = Math.floor(i / W);
  const node = ROUTE === 'own' ? nodeFor(n) : nodeFor(n + 1);
  const level = levelAt(Date.now() - exec.scenario.startTime);
  signalWorkflow(node, `${PREFIX}${n}`, `perf.s${k}`, { name: 'signal', level: `${level}` });
  SIGNALS_SENT.add(1);
}

export function handleSummary(data) {
  const out = summary(PHASE)(data);
  const list = nodes();
  out[`/results/k6-${PHASE}-plan.json`] = JSON.stringify({
    w: W,
    m: M,
    route: ROUTE,
    nodes: list.length,
    crossNode: ROUTE === 'next' && list.length > 1,
    levels: LEVELS,
    holdSecs: HOLD_SECS,
    stages: STAGES,
  });
  return out;
}
