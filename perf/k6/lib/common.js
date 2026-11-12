// Shared helpers for the harness's k6 scripts. Built-in modules only (k6
// 2.3.0): no extensions, no remote jslib. k6 only generates arrivals; the
// harness measures completion, latency and statement counts in SQL.
import http from 'k6/http';
import { check } from 'k6';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/**
 * The nodes under test, from PERF_NODES
 * ("http://maestro-perf-node-1:8080,http://maestro-perf-node-2:8080").
 */
export function nodes() {
  const list = (__ENV.PERF_NODES || '')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  if (list.length === 0) {
    throw new Error('PERF_NODES is empty');
  }
  return list;
}

/** Round-robin: request i goes to node i mod N. */
export function nodeFor(i) {
  const list = nodes();
  return list[i % list.length];
}

/** POST /perf/workflows; the sample answers 202 once the instance row has committed. */
export function startWorkflow(base, body, tags) {
  const res = http.post(`${base}/perf/workflows`, JSON.stringify(body), {
    headers: JSON_HEADERS,
    tags: tags,
  });
  check(res, { 'start 202': (r) => r.status === 202 }, tags);
  return res;
}

/** POST /perf/workflows/{id}/signals/{name}, no payload; 202 even for an unknown id. */
export function signalWorkflow(base, id, name, tags) {
  const url = `${base}/perf/workflows/${encodeURIComponent(id)}/signals/${encodeURIComponent(name)}`;
  const res = http.post(url, null, { tags: tags });
  check(res, { 'signal 202': (r) => r.status === 202 }, tags);
  return res;
}

function metricValue(data, metric, key) {
  const m = data.metrics[metric];
  return m && m.values && m.values[key] !== undefined ? m.values[key] : null;
}

/**
 * A handleSummary factory: the full summary data goes to
 * /results/k6-<phase>.json (the harness reads it), and stdout gets one line
 * per key metric instead of the default report.
 */
export function summary(phase) {
  return function (data) {
    const lines = [
      `k6[${phase}] iterations=${metricValue(data, 'iterations', 'count')}`,
      `k6[${phase}] dropped_iterations=${metricValue(data, 'dropped_iterations', 'count') || 0}`,
      `k6[${phase}] http_reqs=${metricValue(data, 'http_reqs', 'count')}`,
      `k6[${phase}] http_req_failed.rate=${metricValue(data, 'http_req_failed', 'rate')}`,
      `k6[${phase}] http_req_duration.p95=${metricValue(data, 'http_req_duration', 'p(95)')}`,
      `k6[${phase}] checks.passes=${metricValue(data, 'checks', 'passes')} checks.fails=${metricValue(data, 'checks', 'fails')}`,
    ];
    const out = {};
    out[`/results/k6-${phase}.json`] = JSON.stringify(data);
    out.stdout = lines.join('\n') + '\n';
    return out;
  };
}
