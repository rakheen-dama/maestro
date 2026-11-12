// Scenario 1 (s1-throughput): starts chain workflows at a stepped arrival
// rate (open model, ramping-arrival-rate), one level after another, so the
// harness can find the rate the system under test stops keeping up with.
//
// Each level L (starts/s) is two stages: a ramp from the previous level to L,
// then a hold at L. Workflow i is "${PERF_ID_PREFIX}L<level>-<i>" (i =
// iterationInTest, unique in the run) and goes to nodeFor(i); its level is
// the stage the iteration starts in, computed from Date.now() -
// exec.scenario.startTime against the same stage table (the ramp into L
// counts as L). k6 only offers the rate: the start endpoint answers 202 once
// the instance row has committed, BEFORE a workflow slot is taken, so
// throughput and start-to-terminal latency are measured in SQL, not here.
//
// Env: PERF_NODES, PERF_PHASE (names the summary file), PERF_ID_PREFIX,
// PERF_BODY (JSON, the workflow shape), PERF_PROFILE (local | full: the
// profile table below), PERF_K6_VUS / PERF_K6_MAX_VUS (override the
// profile's preAllocatedVUs / maxVUs; the generator-saturation test uses 1/1).
// `k6 inspect` with PERF_PROFILE set shows the resolved scenario; the harness
// reads the stage table from there, so this file is its single source.
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { nodeFor, startWorkflow, summary } from './lib/common.js';

// Levels in starts/s. VUs are sized so the generator can always offer the
// top rate while the start endpoint stalls (VUs needed = rate x request
// seconds): 5 s of stall at the top level, local 80/s x 5 = 400, full
// 120/s x 5 = 600, and ALL of them pre-allocated. k6 allocates VUs above
// preAllocatedVUs mid-test, too slowly for a sudden stall: with 100
// pre-allocated of 400 the full profile dropped 37 iterations while k6 had
// grown to only 121 VUs, during a 3.2 s start stall at 120/s
// (r20261112t211806z). A dropped iteration makes the run INVALID
// (validity.droppedIterations), never a ceiling. Margin: the longest start
// stalls observed at the top level were 2.88 s (full, r20261112t214359z) and
// 3.2 s (r20261112t211806z), so the 5 s budget is only about 1.6-1.7 x the
// worst stall seen; a longer stall drops iterations and the run is INVALID.
const PROFILES = {
  local: { levels: [5, 20, 40, 80], rampSecs: 5, holdSecs: 40, preAllocatedVUs: 400, maxVUs: 400 },
  full: { levels: [5, 10, 20, 40, 60, 80, 120], rampSecs: 10, holdSecs: 120, preAllocatedVUs: 600, maxVUs: 600 },
};

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

const PROFILE_NAME = __ENV.PERF_PROFILE || 'local';
const PROFILE = PROFILES[PROFILE_NAME];
if (PROFILE === undefined) {
  throw new Error(`PERF_PROFILE must be one of ${Object.keys(PROFILES).join(', ')}, got '${PROFILE_NAME}'`);
}
const PRE_ALLOCATED_VUS = intEnv('PERF_K6_VUS', PROFILE.preAllocatedVUs);
// maxVUs below preAllocatedVUs is rejected by k6; an override of both to 1 is valid.
const MAX_VUS = Math.max(PRE_ALLOCATED_VUS, intEnv('PERF_K6_MAX_VUS', PROFILE.maxVUs));
const PHASE = __ENV.PERF_PHASE || 's1';
const BODY = JSON.parse(__ENV.PERF_BODY || '{}');
const PREFIX = __ENV.PERF_ID_PREFIX || '';

const STAGES = [];
// Level i covers [bounds[i].fromMs, bounds[i].toMs) of the scenario's time.
const BOUNDS = [];
{
  let t = 0;
  for (const level of PROFILE.levels) {
    STAGES.push({ target: level, duration: `${PROFILE.rampSecs}s` });
    STAGES.push({ target: level, duration: `${PROFILE.holdSecs}s` });
    const to = t + (PROFILE.rampSecs + PROFILE.holdSecs) * 1000;
    BOUNDS.push({ level: level, fromMs: t, toMs: to });
    t = to;
  }
}

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    s1: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1s',
      startRate: PROFILE.levels[0],
      stages: STAGES,
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

function levelAt(elapsedMs) {
  for (const b of BOUNDS) {
    if (elapsedMs < b.toMs) {
      return b.level;
    }
  }
  // An iteration started in the last instant (or a late timer tick) belongs to the last level.
  return BOUNDS[BOUNDS.length - 1].level;
}

// Per-level start-endpoint timing (a finding about the system under test,
// not the generator): s1_start_ms_L<level> (Trend: max, p95, ...) and
// s1_start_over_1s_L<level> (Counter: starts that took over 1 s). k6/metrics
// is a built-in module like k6/execution: no extension, no remote jslib.
const START_MS = {};
const START_OVER_1S = {};
for (const level of PROFILE.levels) {
  START_MS[level] = new Trend(`s1_start_ms_L${level}`, true);
  START_OVER_1S[level] = new Counter(`s1_start_over_1s_L${level}`);
}

export default function () {
  if (PREFIX === '') {
    throw new Error('PERF_ID_PREFIX is required');
  }
  const i = exec.scenario.iterationInTest;
  const level = levelAt(Date.now() - exec.scenario.startTime);
  const body = Object.assign({}, BODY, { workflowId: `${PREFIX}L${level}-${i}` });
  const res = startWorkflow(nodeFor(i), body, { name: 'start' });
  START_MS[level].add(res.timings.duration);
  START_OVER_1S[level].add(res.timings.duration > 1000 ? 1 : 0);
}

// The plan this run actually executed, next to its summary: the harness
// compares it with what `k6 inspect` resolved (validity.plan), so a profile
// or VU override that reached one and not the other cannot go unnoticed.
export function handleSummary(data) {
  const out = summary(PHASE)(data);
  out[`/results/k6-${PHASE}-plan.json`] = JSON.stringify({
    profile: PROFILE_NAME,
    levels: PROFILE.levels,
    rampSecs: PROFILE.rampSecs,
    holdSecs: PROFILE.holdSecs,
    preAllocatedVUs: PRE_ALLOCATED_VUS,
    maxVUs: MAX_VUS,
  });
  return out;
}
