// Scenario 5 (s5-pool-health): asks one node for its health once a second
// (open model, constant-arrival-rate) for PERF_DURATION seconds and counts
// what came back. Each iteration GETs, in parallel (http.batch), both
//   ${node}/actuator/health/perf  Maestro's component alone, with details
//                                 (sample-perf's health group "perf")
//   ${node}/actuator/health       the root aggregate (Boot's db indicator too)
// each with a 5 s client timeout. k6 records nothing judged here: the
// harness reads these counters from the phase's summary.
//
//   maestro_up / maestro_unknown           components.maestro.status
//   maestro_down_store_timed_out           DOWN, store "timed out", no loop fault
//   maestro_down_store_unreachable         DOWN, store "unreachable", no loop fault
//   maestro_down_other                     DOWN for anything else: a stopped
//                                          poller, sweeper, janitor or renewer,
//                                          renewer failures > 0, or no store detail
//   maestro_no_answer                      no HTTP answer within 5 s (or an
//                                          answer that is not health JSON)
//   root_up / root_down / root_other / root_no_answer   the root aggregate's status
//   maestro_answer_ms / root_answer_ms     time to each answer (answered ones only)
// Every counter is added to (0) on every iteration, so each one is present in
// the summary even when nothing was counted: an absent counter is a harness
// defect, never zero.
//
// Env: PERF_NODES (the first node is probed), PERF_PHASE, PERF_DURATION
// (seconds, positive integer).
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { nodes, summary } from './lib/common.js';

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

const DURATION_SECS = intEnv('PERF_DURATION', 30);
const PHASE = __ENV.PERF_PHASE || 'health-probe';

const C = {
  maestro_up: new Counter('maestro_up'),
  maestro_unknown: new Counter('maestro_unknown'),
  maestro_down_store_timed_out: new Counter('maestro_down_store_timed_out'),
  maestro_down_store_unreachable: new Counter('maestro_down_store_unreachable'),
  maestro_down_other: new Counter('maestro_down_other'),
  maestro_no_answer: new Counter('maestro_no_answer'),
  root_up: new Counter('root_up'),
  root_down: new Counter('root_down'),
  root_other: new Counter('root_other'),
  root_no_answer: new Counter('root_no_answer'),
};
const MAESTRO_MS = new Trend('maestro_answer_ms');
const ROOT_MS = new Trend('root_answer_ms');

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    probe: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: '1s',
      duration: `${DURATION_SECS}s`,
      // Both requests run in parallel, so an iteration lasts at most 5 s:
      // 5 busy VUs carry 1/s. All 8 are pre-allocated: with the plan's 4,
      // k6 grew the pool mid-test too slowly when the root aggregate stalled
      // 5 s and dropped 2 of 35 iterations (r20261117t125408z, exhausted).
      preAllocatedVUs: 8,
      maxVUs: 12,
    },
  },
  // Advisory only (D4): crossing it exits 99, which the harness records.
  thresholds: {
    maestro_no_answer: ['count<1'],
  },
};

function json(res) {
  try {
    return JSON.parse(res.body);
  } catch (e) {
    return null;
  }
}

// An HTTP answer of any status. Not `!res.error_code`: k6 sets error_code
// for every 4xx/5xx too (1503 for a 503), so a DOWN component's 503 would
// count as "no answer" (it did, in r20261117t152847z); a timeout or a
// refused connection has status 0.
function answered(res) {
  return res.status !== 0;
}

// A DOWN that a background loop explains, not the store: any *Running detail
// that is false, or renewer failures > 0.
function loopFault(details) {
  if (!details) {
    return false;
  }
  for (const k of Object.keys(details)) {
    if (k.endsWith('Running') && details[k] === false) {
      return true;
    }
  }
  return (details.instanceLockRenewerConsecutiveFailures || 0) > 0;
}

export default function () {
  for (const k of Object.keys(C)) {
    C[k].add(0);
  }
  const base = nodes()[0];
  const params = (name) => ({ timeout: '5s', tags: { name: name } });
  const [perf, root] = http.batch([
    ['GET', `${base}/actuator/health/perf`, null, params('perf')],
    ['GET', `${base}/actuator/health`, null, params('root')],
  ]);

  const m = answered(perf) ? json(perf) : null;
  const maestro = m && m.components ? m.components.maestro : null;
  if (!maestro || !maestro.status) {
    C.maestro_no_answer.add(1);
  } else {
    MAESTRO_MS.add(perf.timings.duration);
    const details = maestro.details || {};
    if (maestro.status === 'UP') {
      C.maestro_up.add(1);
    } else if (maestro.status === 'UNKNOWN') {
      C.maestro_unknown.add(1);
    } else if (maestro.status === 'DOWN' && !loopFault(details) && details.store === 'timed out') {
      C.maestro_down_store_timed_out.add(1);
    } else if (maestro.status === 'DOWN' && !loopFault(details) && details.store === 'unreachable') {
      C.maestro_down_store_unreachable.add(1);
    } else {
      C.maestro_down_other.add(1);
    }
  }

  const r = answered(root) ? json(root) : null;
  if (!r || !r.status) {
    C.root_no_answer.add(1);
  } else {
    ROOT_MS.add(root.timings.duration);
    if (r.status === 'UP') {
      C.root_up.add(1);
    } else if (r.status === 'DOWN') {
      C.root_down.add(1);
    } else {
      C.root_other.add(1);
    }
  }
}

export function handleSummary(data) {
  return summary(PHASE)(data);
}
