package io.b2mash.maestro.messaging.kafka;

import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.NullMarked;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/**
 * Builds the error handler both Maestro consumer paths share: bounded,
 * backed-off redelivery followed by a dead-letter topic.
 *
 * <p>A handler exception means the message was not processed — on the engine
 * signal channel it means the signal is not yet in Postgres — so the offset
 * must not be committed. {@link DefaultErrorHandler} seeks back to the failed
 * record and retries it in place for the duration of the backoff, instead of
 * committing past it. Maestro's listener containers run with the default
 * concurrency of one consumer thread per topic, so that thread owns every
 * partition assigned to it: the backoff blocks consumption of the whole
 * topic on this node for its duration, not just the failed record's
 * partition. That is deliberate: it preserves per-workflow ordering, and
 * during a store outage the signal channel should pause rather than churn.
 * Once the attempt budget is spent the record is published to
 * {@code <topic><deadLetterSuffix>}, keeping its key and value and gaining
 * {@code kafka_dlt-*} headers describing the original coordinates and the
 * failure.
 *
 * <p>Maestro never creates topics, dead-letter topics included: they are
 * pre-declared by the operator. If the dead-letter topic is missing, the
 * recoverer's publish fails, the offset is still not committed and the record
 * is attempted again — consumption stalls noisily instead of losing a message.
 *
 * <p><b>Thread safety:</b> This class is a stateless factory; the handlers it
 * returns are managed by, and confined to, their listener container.
 */
@NullMarked
public final class KafkaRedeliveryErrorHandlers {

    private KafkaRedeliveryErrorHandlers() {
    }

    /**
     * Creates a dead-lettering error handler.
     *
     * @param template         the producer used to publish exhausted records
     * @param maxAttempts      total delivery attempts, including the first
     * @param initialInterval  backoff before the second attempt
     * @param multiplier       factor applied to the backoff after each failure
     * @param maxInterval      ceiling for the computed backoff
     * @param deadLetterSuffix appended to the source topic to name the
     *                         dead-letter topic (e.g. {@code ".DLT"})
     * @return an error handler for a listener container
     */
    public static CommonErrorHandler deadLettering(
            KafkaOperations<String, byte[]> template,
            int maxAttempts,
            Duration initialInterval,
            double multiplier,
            Duration maxInterval,
            String deadLetterSuffix
    ) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(
                        record.topic() + deadLetterSuffix, record.partition()));

        // ExponentialBackOff.maxAttempts counts the *intervals* it hands out,
        // i.e. the redeliveries, so the initial attempt is subtracted —
        // maxAttempts=1 means no redelivery at all.
        // (Spring Framework 7 folded ExponentialBackOffWithMaxRetries into
        // ExponentialBackOff.setMaxAttempts; the semantics are the same.)
        var backOff = new ExponentialBackOff();
        backOff.setInitialInterval(initialInterval.toMillis());
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxAttempts(Math.max(0, maxAttempts - 1));

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
