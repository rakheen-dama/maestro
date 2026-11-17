// Starts PERF_COUNT workflows as fast as PERF_VUS VUs can (shared-iterations).
// Workflow i is "${PERF_ID_PREFIX}${PERF_ID_OFFSET + i}", its body is
// PERF_BODY (JSON) plus that workflowId, and it goes to nodeFor(i).
//
// Env: PERF_NODES, PERF_ID_PREFIX, PERF_ID_OFFSET (0), PERF_COUNT, PERF_VUS,
// PERF_MAX_DURATION (5m), PERF_BODY, PERF_PHASE (names the summary file).
import exec from 'k6/execution';
import { nodeFor, startWorkflow, summary } from './lib/common.js';

function intEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const v = parseInt(raw, 10);
  if (!Number.isFinite(v) || v < 0) {
    throw new Error(`${name} must be a non-negative integer, got '${raw}'`);
  }
  return v;
}

const COUNT = intEnv('PERF_COUNT', 1);
const OFFSET = intEnv('PERF_ID_OFFSET', 0);
// shared-iterations rejects more VUs than iterations.
const VUS = Math.max(1, Math.min(intEnv('PERF_VUS', 10), COUNT));
const PHASE = __ENV.PERF_PHASE || 'start-batch';
const BODY = JSON.parse(__ENV.PERF_BODY || '{}');
const PREFIX = __ENV.PERF_ID_PREFIX || '';

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    batch: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: COUNT,
      maxDuration: __ENV.PERF_MAX_DURATION || '5m',
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
  const i = OFFSET + exec.scenario.iterationInTest;
  const body = Object.assign({}, BODY, { workflowId: `${PREFIX}${i}` });
  startWorkflow(nodeFor(i), body, { name: 'start' });
}

export function handleSummary(data) {
  return summary(PHASE)(data);
}
