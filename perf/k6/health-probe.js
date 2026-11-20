// Scenario 5 (s5-pool-health): asks one node for its health once a second
// (open model, constant-arrival-rate) for PERF_DURATION seconds and counts
// what came back. Each iteration GETs, in parallel (http.batch),
//   ${node}/actuator/health/perf            Maestro's component alone, with details
//                                           (sample-perf's health group "perf")
//   ${node}/actuator/health                 the root aggregate (Boot's db indicator too)
//   ${node}/actuator/health/maestroLiveness the liveness component (status only)
// each with a 5 s client timeout. k6 records nothing judged here: the
// harness reads these counters from the phase's summary.
//
// The evaluator's counters (perf/eval/s5-pool-health.jq; exactly one per
// answer, their sum is the answers counted):
//   maestro_up / maestro_unknown           components.maestro.status
//   maestro_down_store_timed_out           DOWN, store "timed out", no loop or renewer cause.
//                                          Kept for the evaluator; the indicator reads a
//                                          timed-out probe UNKNOWN, so such a DOWN always
//                                          has a renewer cause and counts under _other
//   maestro_down_store_unreachable         DOWN, store "unreachable", no loop or renewer cause
//   maestro_down_other                     DOWN for anything else: every DOWN with a
//                                          loop or renewer cause (broken down below),
//                                          or with no details to explain it
//   maestro_no_answer                      no HTTP answer within 5 s (or an
//                                          answer that is not health JSON)
//
// The causes, read from the details MaestroHealthIndicator writes
// (docs/operations.md section 14). Each is a sub-count of the counter named,
// never added to the evaluator's sum; one DOWN answer can count under several
// causes. The first time a VU sees a cause it logs it with the loops it names
// ("maestro DOWN: failing loop timer-poller") to the phase's k6 log.
//   maestro_down_loop_dead          (of _other) a loop whose state is "dead"
//   maestro_down_loop_stopped       (of _other) a *Running detail false: started, no
//                                   longer running, not dead
//   maestro_down_loop_failing       (of _other) a critical loop (timer-poller,
//                                   recovery-poller, startup-recovery,
//                                   instance-lock-renewer) failing, the probe answered
//   maestro_down_renewer_failing    (of _other) instanceLockRenewerConsecutiveFailures >= 2
//   maestro_down_renewer_stale      (of _other) DOWN that no other cause explains: by
//                                   elimination a held instance lock a whole lock TTL
//                                   without renewal (the indicator has no detail for its age)
//   maestro_unknown_probe_timeout   (of maestro_unknown) store "timed out": the 2 s
//                                   store probe did not finish
//   maestro_unknown_loop_failing    (of maestro_unknown) a critical loop failing too
//                                   (a timed-out probe reads UNKNOWN, not DOWN)
//   maestro_up_degraded             (of maestro_up) a non-empty "degraded" list: a
//                                   housekeeping loop failing or the wake listener
//                                   disconnected; UP, never out of readiness
//
//   root_up / root_down / root_other / root_no_answer   the root aggregate's status
//   liveness_up / liveness_down     maestroLiveness status; DOWN only for a dead loop
//   liveness_other / liveness_no_answer  any other status / no answer (404 on an
//                                   engine without maestroLiveness)
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
  maestro_down_loop_dead: new Counter('maestro_down_loop_dead'),
  maestro_down_loop_stopped: new Counter('maestro_down_loop_stopped'),
  maestro_down_loop_failing: new Counter('maestro_down_loop_failing'),
  maestro_down_renewer_failing: new Counter('maestro_down_renewer_failing'),
  maestro_down_renewer_stale: new Counter('maestro_down_renewer_stale'),
  maestro_unknown_probe_timeout: new Counter('maestro_unknown_probe_timeout'),
  maestro_unknown_loop_failing: new Counter('maestro_unknown_loop_failing'),
  maestro_up_degraded: new Counter('maestro_up_degraded'),
  root_up: new Counter('root_up'),
  root_down: new Counter('root_down'),
  root_other: new Counter('root_other'),
  root_no_answer: new Counter('root_no_answer'),
  liveness_up: new Counter('liveness_up'),
  liveness_down: new Counter('liveness_down'),
  liveness_other: new Counter('liveness_other'),
  liveness_no_answer: new Counter('liveness_no_answer'),
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
      // The requests run in parallel, so an iteration lasts at most 5 s:
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

// MaestroHealthIndicator's critical loops: one failing reads DOWN while the
// store probe answers, UNKNOWN while it times out.
const CRITICAL_LOOPS = ['timer-poller', 'recovery-poller', 'startup-recovery', 'instance-lock-renewer'];

// Two consecutive failed renewal passes read DOWN (RENEWER_FAULT_STREAK).
const RENEWER_FAULT_STREAK = 2;

function loopsWhere(details, predicate) {
  const loops = details.loops && typeof details.loops === 'object' ? details.loops : {};
  return Object.keys(loops).filter((name) => loops[name] && predicate(name, loops[name]));
}

// Every loop and renewer cause the details show, each with the loops it names.
// The store is not a cause here: it has its own counters.
function causes(details) {
  const dead = loopsWhere(details, (name, loop) => loop.state === 'dead');
  // timerPollerRunning: false names the loop timer-poller; a dead loop is not
  // running either, and is reported as dead only.
  const stopped = Object.keys(details)
    .filter((k) => k.endsWith('Running') && details[k] === false)
    .map((k) => k.slice(0, -'Running'.length).replace(/[A-Z]/g, (ch) => `-${ch.toLowerCase()}`))
    .filter((name) => !dead.includes(name));
  const failing = loopsWhere(details, (name, loop) => loop.failing === true && CRITICAL_LOOPS.includes(name));
  const renewerFailures = details.instanceLockRenewerConsecutiveFailures || 0;
  return {
    dead: dead,
    stopped: stopped,
    failing: failing,
    renewerFailing: renewerFailures >= RENEWER_FAULT_STREAK ? renewerFailures : 0,
  };
}

// Logs each distinct cause once per VU, so the phase's k6 log names it
// without a line per second.
const seen = new Set();
function report(line) {
  if (!seen.has(line)) {
    seen.add(line);
    console.warn(`health-probe ${PHASE}: ${line}`);
  }
}

function classifyDown(details) {
  const c = causes(details);
  let explained = false;
  if (c.dead.length > 0) {
    C.maestro_down_loop_dead.add(1);
    report(`maestro DOWN: dead loop ${c.dead.join(', ')}`);
    explained = true;
  }
  if (c.stopped.length > 0) {
    C.maestro_down_loop_stopped.add(1);
    report(`maestro DOWN: loop no longer running ${c.stopped.join(', ')}`);
    explained = true;
  }
  if (c.renewerFailing > 0) {
    C.maestro_down_renewer_failing.add(1);
    report(`maestro DOWN: instance-lock renewer failed ${c.renewerFailing} passes in a row`);
    explained = true;
  }
  // MaestroHealthIndicator reads a failing critical loop DOWN only while the
  // store probe answered: with the store unreachable the store explains it.
  if (c.failing.length > 0 && details.store === 'reachable') {
    C.maestro_down_loop_failing.add(1);
    report(`maestro DOWN: failing loop ${c.failing.join(', ')}`);
    explained = true;
  }
  if (explained) {
    C.maestro_down_other.add(1);
  } else if (details.store === 'unreachable') {
    C.maestro_down_store_unreachable.add(1);
    report('maestro DOWN: store unreachable');
  } else if (details.store === 'timed out' || details.store === 'reachable') {
    // Nothing above, and the store did not refuse: the one DOWN cause left is
    // a held instance lock a whole TTL without renewal (the renewer stuck in
    // renewAll, or its first renewal failed). It reads DOWN even with a
    // timed-out probe, so a timed-out store is no explanation on its own.
    C.maestro_down_renewer_stale.add(1);
    C.maestro_down_other.add(1);
    report(`maestro DOWN: stale instance-lock renewer (store ${details.store})`);
  } else {
    C.maestro_down_other.add(1);
    report(`maestro DOWN: no cause in the details (store ${JSON.stringify(details.store)})`);
  }
}

function classifyMaestro(maestro) {
  const details = maestro.details || {};
  if (maestro.status === 'UP') {
    C.maestro_up.add(1);
    if (Array.isArray(details.degraded) && details.degraded.length > 0) {
      C.maestro_up_degraded.add(1);
      report(`maestro UP, degraded: ${details.degraded.join(', ')}`);
    }
  } else if (maestro.status === 'UNKNOWN') {
    C.maestro_unknown.add(1);
    if (details.store === 'timed out') {
      C.maestro_unknown_probe_timeout.add(1);
      report('maestro UNKNOWN: store probe timed out');
    }
    const failing = causes(details).failing;
    if (failing.length > 0) {
      C.maestro_unknown_loop_failing.add(1);
      report(`maestro UNKNOWN: store probe timed out while loop ${failing.join(', ')} is failing`);
    }
  } else if (maestro.status === 'DOWN') {
    classifyDown(details);
  } else {
    C.maestro_down_other.add(1);
    report(`maestro ${maestro.status}: not a status this probe knows`);
  }
}

export default function () {
  for (const k of Object.keys(C)) {
    C[k].add(0);
  }
  const base = nodes()[0];
  const params = (name) => ({ timeout: '5s', tags: { name: name } });
  const [perf, root, liveness] = http.batch([
    ['GET', `${base}/actuator/health/perf`, null, params('perf')],
    ['GET', `${base}/actuator/health`, null, params('root')],
    ['GET', `${base}/actuator/health/maestroLiveness`, null, params('liveness')],
  ]);

  const m = answered(perf) ? json(perf) : null;
  const maestro = m && m.components ? m.components.maestro : null;
  if (!maestro || !maestro.status) {
    C.maestro_no_answer.add(1);
  } else {
    MAESTRO_MS.add(perf.timings.duration);
    classifyMaestro(maestro);
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

  const l = answered(liveness) ? json(liveness) : null;
  if (!l || !l.status) {
    C.liveness_no_answer.add(1);
  } else if (l.status === 'UP') {
    C.liveness_up.add(1);
  } else if (l.status === 'DOWN') {
    C.liveness_down.add(1);
    report('maestroLiveness DOWN: a dead loop (see the maestro component\'s loops)');
  } else {
    C.liveness_other.add(1);
  }
}

export function handleSummary(data) {
  return summary(PHASE)(data);
}
