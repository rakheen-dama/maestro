-- Summary of one activity-sampler label (perf/sql/activity-sampler.sql):
--   psql -v label=<label> [-v min=<n>, default 1]
--        [-v from=<timestamptz> -v to=<timestamptz>: only the samples taken
--         in [from, to], default every sample of the label]
-- A sample "matches" when at least :min engine backends were active in a
-- query matching the sampler's pattern. Returns
--   {samples, firstAt, lastAt, maxGapSecs (largest gap between consecutive
--    samples: a stalled sampler or an unreachable database), matchingSamples,
--    firstMatchAt, lastMatchAt, spanSamples (samples from the first matching
--    one to the last, inclusive), matchingInSpan, maxMatching, min,
--    spanMaxGapSecs (the largest gap between consecutive samples inside the
--    span), spanSecs (last matching - first matching sample), and
--    busyTimeFraction: the span's time covered by matching samples, each
--    sample weighted by the time to the next one (a sample stands for the
--    interval until the next was taken), / spanSecs. Unlike
--    matchingInSpan / spanSamples it does not over-weight a stretch where
--    the sampler itself was slow}.
-- Runs as postgres.
\if :{?min}
\else
\set min 1
\endif
\if :{?from}
\else
\set from '-infinity'
\endif
\if :{?to}
\else
\set to 'infinity'
\endif
WITH s AS (
    SELECT at, matching, maestro_active,
           extract(epoch FROM at - lag(at) OVER (ORDER BY at)) AS gap
    FROM perf_harness.activity_sample
    WHERE label = :'label' AND at BETWEEN :'from'::timestamptz AND :'to'::timestamptz
), m AS (
    SELECT min(at) AS first_match, max(at) AS last_match, count(*) AS n
    FROM s WHERE matching >= :min
), sp AS (
    SELECT s.at, s.matching, s.gap,
           extract(epoch FROM lead(s.at) OVER (ORDER BY s.at) - s.at) AS weight
    FROM s, m WHERE s.at BETWEEN m.first_match AND m.last_match
)
SELECT json_build_object(
    'samples', (SELECT count(*) FROM s),
    'firstAt', (SELECT min(at) FROM s),
    'lastAt', (SELECT max(at) FROM s),
    'maxGapSecs', (SELECT round(coalesce(max(gap), 0)::numeric, 3) FROM s),
    'matchingSamples', m.n,
    'firstMatchAt', m.first_match,
    'lastMatchAt', m.last_match,
    'spanSamples', (SELECT count(*) FROM s WHERE at BETWEEN m.first_match AND m.last_match),
    'matchingInSpan', (SELECT count(*) FROM s WHERE at BETWEEN m.first_match AND m.last_match AND matching >= :min),
    'maxMatching', (SELECT coalesce(max(matching), 0) FROM s),
    'min', :min,
    'spanMaxGapSecs', (SELECT round(coalesce(max(gap) FILTER (WHERE at > m.first_match), 0)::numeric, 3) FROM sp),
    'spanSecs', round(extract(epoch FROM m.last_match - m.first_match)::numeric, 3),
    'busyTimeFraction', (SELECT CASE WHEN extract(epoch FROM m.last_match - m.first_match) > 0
                                     THEN round((coalesce(sum(weight) FILTER (WHERE matching >= :min), 0)
                                                 / extract(epoch FROM m.last_match - m.first_match))::numeric, 4)
                                END FROM sp))
FROM m;
