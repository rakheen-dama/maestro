// Starts workflows at a constant arrival rate (open model,
// constant-arrival-rate): PERF_RATE starts per PERF_TIME_UNIT seconds for
// PERF_DURATION seconds. Workflow i is "${PERF_ID_PREFIX}${PERF_ID_OFFSET + i}"
// (i = iterationInTest, unique in the phase), its body is PERF_BODY (JSON)
// plus that workflowId, and it goes to nodeFor(PERF_ID_OFFSET + i). Scenario
// 3 uses it twice:
//  - probes: a few short chain runs started while the parked population
//    sits, whose start-to-terminal time the harness reads from the instance
//    rows (k6 only offers the rate: the start endpoint answers 202 once the
//    row has committed);
//  - seeding: PERF_RATE = PERF_COUNT parked runs per one wake-recheck interval
//    (PERF_TIME_UNIT = PERF_DURATION = that interval), so the population's
//    re-check phases are spread evenly over the interval instead of bunched
//    into the few seconds an unpaced batch takes.
// k6's constant-arrival-rate can start one iteration more than rate x
// duration / timeUnit (observed: 1001 for 1000): with PERF_COUNT set, an
// iteration i >= PERF_COUNT starts nothing, so exactly PERF_COUNT start.
//
// Env: PERF_NODES, PERF_PHASE (names the summary and plan files),
// PERF_ID_PREFIX, PERF_ID_OFFSET (0), PERF_BODY, PERF_RATE (positive
// integer), PERF_TIME_UNIT (seconds, default 1), PERF_DURATION (seconds,
// positive integer), PERF_COUNT (optional cap), PERF_K6_VUS /
// PERF_K6_MAX_VUS (preAllocatedVUs / maxVUs, default 10 / 50).
import exec from 'k6/execution';
import { nodeFor, startWorkflow, summary } from './lib/common.js';

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

const RATE = intEnv('PERF_RATE', 1);
const TIME_UNIT_SECS = intEnv('PERF_TIME_UNIT', 1);
const DURATION_SECS = intEnv('PERF_DURATION', 1);
const OFFSET = __ENV.PERF_ID_OFFSET === '0' ? 0 : intEnv('PERF_ID_OFFSET', 0);
const COUNT = intEnv('PERF_COUNT', null);
const PRE_ALLOCATED_VUS = intEnv('PERF_K6_VUS', 10);
// maxVUs below preAllocatedVUs is rejected by k6.
const MAX_VUS = Math.max(PRE_ALLOCATED_VUS, intEnv('PERF_K6_MAX_VUS', 50));
const PHASE = __ENV.PERF_PHASE || 'start-rate';
const BODY = JSON.parse(__ENV.PERF_BODY || '{}');
const PREFIX = __ENV.PERF_ID_PREFIX || '';

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    rate: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: `${TIME_UNIT_SECS}s`,
      duration: `${DURATION_SECS}s`,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  // Advisory only (D4): a crossed threshold exits 99, which the harness
  // records and does not fail on. They also put the {name:start} sub-metrics
  // into the summary.
  thresholds: {
    'http_req_failed{name:start}': ['rate<0.01'],
    'http_req_duration{name:start}': ['p(95)<2000'],
  },
};

export default function () {
  if (PREFIX === '') {
    throw new Error('PERF_ID_PREFIX is required');
  }
  const it = exec.scenario.iterationInTest;
  if (COUNT !== null && it >= COUNT) {
    return;
  }
  const i = OFFSET + it;
  const body = Object.assign({}, BODY, { workflowId: `${PREFIX}${i}` });
  startWorkflow(nodeFor(i), body, { name: 'start' });
}

// The plan this phase actually executed, next to its summary: the harness
// compares it with what it asked for (validity.plan).
export function handleSummary(data) {
  const out = summary(PHASE)(data);
  out[`/results/k6-${PHASE}-plan.json`] = JSON.stringify({
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: `${TIME_UNIT_SECS}s`,
    durationSecs: DURATION_SECS,
    offset: OFFSET,
    count: COUNT,
    preAllocatedVUs: PRE_ALLOCATED_VUS,
    maxVUs: MAX_VUS,
    idPrefix: PREFIX,
  });
  return out;
}
