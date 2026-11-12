package io.b2mash.maestro.samples.perf.web;

import java.util.List;

/**
 * Body of {@code GET /perf/info}: which build and which JVM answered, and the
 * settings the running application actually bound — read from the live beans,
 * never echoed from the environment. The harness uses it to reject a run served
 * by a stale or foreign process, and to report effective rather than requested
 * settings.
 *
 * @param buildCommit                      {@code gitCommit} baked into the jar's build info
 *                                         ({@code -PperfGitCommit}); {@code "unknown"} without build info
 * @param buildTime                        build time (ISO-8601 instant), or {@code "unknown"}
 * @param version                          project version from the build info, or {@code "unknown"}
 * @param hostname                         this host's name (the container's, under Compose)
 * @param pid                              this JVM's process ID
 * @param jvmStartMillis                   this JVM's start time, epoch milliseconds
 * @param javaVersion                      the running Java version
 * @param availableProcessors              processors visible to the JVM
 * @param maxHeapBytes                     maximum heap, bytes
 * @param serviceName                      {@code maestro.service-name} as bound
 * @param adminEventsEnabled               {@code maestro.admin.events.enabled} as bound — the value the
 *                                         engine gates lifecycle publishing on
 * @param workerDefaultConcurrency         {@code maestro.worker.default-concurrency} as bound
 * @param workerDefaultActivityConcurrency {@code maestro.worker.default-activity-concurrency} as bound
 * @param workerTaskQueues                 names of the configured {@code maestro.worker.task-queues}
 * @param wakeRecheckInterval              {@code maestro.signal.wake-recheck-interval}, ISO-8601
 * @param recoveryPollInterval             {@code maestro.recovery.poll-interval}, ISO-8601
 * @param timerPollInterval                {@code maestro.timer.poll-interval}, ISO-8601 — how often the
 *                                         timer poller looks for due timers, so the latency a
 *                                         sleep-mode park run adds on top of its sleep
 * @param hikariMaximumPoolSize            the live Hikari pool's maximum size, or {@code -1} if the
 *                                         {@code DataSource} is not Hikari
 */
public record PerfInfo(
        String buildCommit,
        String buildTime,
        String version,
        String hostname,
        long pid,
        long jvmStartMillis,
        String javaVersion,
        int availableProcessors,
        long maxHeapBytes,
        String serviceName,
        boolean adminEventsEnabled,
        int workerDefaultConcurrency,
        int workerDefaultActivityConcurrency,
        List<String> workerTaskQueues,
        String wakeRecheckInterval,
        String recoveryPollInterval,
        String timerPollInterval,
        int hikariMaximumPoolSize
) {}
