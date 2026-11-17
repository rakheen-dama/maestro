-- Scenario 6 (s6-retention): seeds the eight sets of the plan (section 4.4)
-- through perf_harness.s6_clone (s6-template.sql), under :'prefix'
-- ("<runId>-s6-") + "<set>-<i>". Sizes come as psql variables:
--   -v rc= -v rt= -v rf= -v young= -v jown= -v jfailed= -v jforeign= -v xforeign=
--   set       status      completed  service       extra unconsumed signal
--   rc        COMPLETED   40 d ago   perf          no    (retention: purged up to the budget)
--   rt        TERMINATED  40 d       perf          no    (purged)
--   rf        FAILED      100 d      perf          no    (purged)
--   young     COMPLETED   1 d        perf          no    (kept)
--   jown      COMPLETED   10 d       perf          yes   (instance kept; the janitor deletes the signal)
--   jfailed   FAILED      10 d       perf          yes   (kept: the janitor takes COMPLETED/TERMINATED only)
--   jforeign  COMPLETED   10 d       perf-foreign  yes   (the janitor's service scoping, 3.11b)
--   xforeign  TERMINATED  40 d       perf-foreign  no    (retention's service scoping: an observation)
-- xforeign is TERMINATED, not COMPLETED as the plan wrote: a COMPLETED set
-- shares the COMPLETED budget (100 batches x 500) with rc, oldest first, so
-- whether rc's remainder is 1,000 would depend on whether foreign rows are
-- candidates (the very thing it observes). TERMINATED's budget is far from
-- spent (rt + xforeign = 6,000 at most), so rc's remainder is the same on
-- every build. Returns {sets: {name: n}, seconds, at}. Runs as postgres.
\o /dev/null
SET client_min_messages = warning;
CREATE TEMP TABLE s6_t0 AS SELECT clock_timestamp() AS t0;
SELECT perf_harness.s6_clone(:'prefix' || 'rc-', :rc, 'COMPLETED', interval '40 days', 'perf', false);
SELECT perf_harness.s6_clone(:'prefix' || 'rt-', :rt, 'TERMINATED', interval '40 days', 'perf', false);
SELECT perf_harness.s6_clone(:'prefix' || 'rf-', :rf, 'FAILED', interval '100 days', 'perf', false);
SELECT perf_harness.s6_clone(:'prefix' || 'young-', :young, 'COMPLETED', interval '1 day', 'perf', false);
SELECT perf_harness.s6_clone(:'prefix' || 'jown-', :jown, 'COMPLETED', interval '10 days', 'perf', true);
SELECT perf_harness.s6_clone(:'prefix' || 'jfailed-', :jfailed, 'FAILED', interval '10 days', 'perf', true);
SELECT perf_harness.s6_clone(:'prefix' || 'jforeign-', :jforeign, 'COMPLETED', interval '10 days', 'perf-foreign', true);
SELECT perf_harness.s6_clone(:'prefix' || 'xforeign-', :xforeign, 'TERMINATED', interval '40 days', 'perf-foreign', false);
ANALYZE maestro_workflow_instance;
ANALYZE maestro_workflow_event;
ANALYZE maestro_workflow_timer;
ANALYZE maestro_workflow_signal;
\o
SELECT json_build_object(
    'sets', json_build_object('rc', :rc, 'rt', :rt, 'rf', :rf, 'young', :young, 'jown', :jown,
                              'jfailed', :jfailed, 'jforeign', :jforeign, 'xforeign', :xforeign),
    'seconds', round(extract(epoch FROM clock_timestamp() - (SELECT t0 FROM s6_t0))::numeric, 3),
    'at', now());
