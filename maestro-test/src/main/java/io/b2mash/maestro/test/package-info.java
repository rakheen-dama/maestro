/**
 * Maestro Test — in-memory SPIs, controllable clock, TestWorkflowEnvironment.
 *
 * <p>{@link io.b2mash.maestro.test.TestWorkflowEnvironment} runs the real
 * engine over in-memory stores: start workflows, deliver signals, advance a
 * controllable clock, and — with
 * {@link io.b2mash.maestro.test.TestWorkflowEnvironment#crashAfter(int)} and
 * friends — crash the node part-way through a run and
 * {@linkplain io.b2mash.maestro.test.TestWorkflowEnvironment#recover() recover}
 * it, so a test exercises replay: result deserialisation, the replay guards
 * and the activity-argument fingerprint, exactly as a production restart
 * does. {@link io.b2mash.maestro.test.DeterminismChecker} automates both the
 * run-to-run and the crash-at-every-step checks.
 */
@NullMarked
package io.b2mash.maestro.test;

import org.jspecify.annotations.NullMarked;
