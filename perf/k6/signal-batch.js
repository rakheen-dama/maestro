// Sends one signal to each of PERF_COUNT workflows, as fast as PERF_VUS VUs
// can (shared-iterations): iteration i signals
// "${PERF_ID_PREFIX}${PERF_ID_OFFSET + i}" with PERF_SIGNAL (no payload).
//
// Routing: the signal for workflow n goes to nodeFor(n + 1). start-rate.js
// (Scenario 3's seeding) started workflow n on nodeFor(n), and a run
// executes on the node that started it, so with two or more replicas EVERY
// signal lands on a node that does not own the run: the signal row commits
// there and the parked await on the owning node must be woken across nodes
// (Postgres NOTIFY, or at worst its next wake re-check). With one replica
// nodeFor(n + 1) is that replica.
//
// Env: PERF_NODES, PERF_PHASE (names the summary and plan files),
// PERF_ID_PREFIX, PERF_ID_OFFSET (0), PERF_COUNT, PERF_VUS (10),
// PERF_MAX_DURATION (5m), PERF_SIGNAL (perf.release).
import exec from 'k6/execution';
import { nodeFor, nodes, signalWorkflow, summary } from './lib/common.js';

function intEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const v = parseInt(raw, 10);
  if (!Number.isFinite(v) || v < 0 || String(v) !== raw.trim()) {
    throw new Error(`${name} must be a non-negative integer, got '${raw}'`);
  }
  return v;
}

const COUNT = Math.max(1, intEnv('PERF_COUNT', 1));
const OFFSET = intEnv('PERF_ID_OFFSET', 0);
// shared-iterations rejects more VUs than iterations.
const VUS = Math.max(1, Math.min(intEnv('PERF_VUS', 10), COUNT));
const PHASE = __ENV.PERF_PHASE || 'signal-batch';
const PREFIX = __ENV.PERF_ID_PREFIX || '';
const SIGNAL = __ENV.PERF_SIGNAL || 'perf.release';
const MAX_DURATION = __ENV.PERF_MAX_DURATION || '5m';

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    signals: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: COUNT,
      maxDuration: MAX_DURATION,
    },
  },
  // Advisory only (D4): a crossed threshold exits 99, recorded, not failed.
  thresholds: {
    'http_req_failed{name:signal}': ['rate<0.01'],
    'http_req_duration{name:signal}': ['p(95)<2000'],
  },
};

export default function () {
  if (PREFIX === '') {
    throw new Error('PERF_ID_PREFIX is required');
  }
  const n = OFFSET + exec.scenario.iterationInTest;
  signalWorkflow(nodeFor(n + 1), `${PREFIX}${n}`, SIGNAL, { name: 'signal' });
}

// The plan this phase executed (validity.plan), and whether its routing made
// every signal cross nodes (true when more than one node is under test).
export function handleSummary(data) {
  const out = summary(PHASE)(data);
  out[`/results/k6-${PHASE}-plan.json`] = JSON.stringify({
    executor: 'shared-iterations',
    count: COUNT,
    offset: OFFSET,
    vus: VUS,
    signal: SIGNAL,
    idPrefix: PREFIX,
    nodes: nodes().length,
    crossNode: nodes().length > 1,
  });
  return out;
}
