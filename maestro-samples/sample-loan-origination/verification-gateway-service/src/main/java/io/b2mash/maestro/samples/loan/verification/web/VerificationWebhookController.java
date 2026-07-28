package io.b2mash.maestro.samples.loan.verification.web;

import io.b2mash.maestro.samples.loan.verification.activity.VerificationMessagingActivities;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * Webhook-style endpoint that delivers a {@code verification.result} signal
 * into the loan-application service's {@code loan-{loanId}} workflow —
 * bypassing the {@code VerificationWorkflow} entirely, like a real
 * third-party provider calling back at an arbitrary time.
 *
 * <h2>Delivery mechanism (documented per SPEC)</h2>
 * <p>Of the options the SPEC allows (engine-level
 * {@code WorkflowMessaging.publishSignal}, a remote REST call to
 * loan-application, or the shared results topic), this controller publishes a
 * properly-shaped {@link VerificationResult} to the pre-created
 * {@code loans.verification.results} Kafka topic — the simplest correct
 * mechanism:
 * <ul>
 *   <li>It reuses the exact production path: the loan-application service's
 *       listener on that topic routes the event into {@code loan-{loanId}}
 *       as a {@code verification.result} signal.</li>
 *   <li>No coupling to loan-application's engine-internal signal channels or
 *       REST surface.</li>
 *   <li>Maestro persists signals before consumption, so a webhook fired
 *       before the loan workflow reaches {@code awaitSignal} — or before the
 *       workflow even exists — is stored and consumed later (pre-delivery /
 *       orphan adoption). This is the out-of-order case the E2E exercises.</li>
 * </ul>
 *
 * <p>Calling the {@link VerificationMessagingActivities} bean directly here
 * is a plain method call — the {@code @Activity} interface is only memoized
 * when invoked from inside a workflow via an {@code @ActivityStub} proxy.
 */
@RestController
public class VerificationWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(VerificationWebhookController.class);
    private static final Set<String> VALID_TYPES = Set.of("credit", "employment", "appraisal");

    private final VerificationMessagingActivities messaging;

    public VerificationWebhookController(VerificationMessagingActivities messaging) {
        this.messaging = messaging;
    }

    /**
     * Webhook payload.
     *
     * @param approved whether the out-of-band verification passed
     * @param details  optional human-readable detail
     */
    public record WebhookResultRequest(boolean approved, @Nullable String details) {}

    @PostMapping("/webhooks/{type}/{loanId}")
    public ResponseEntity<Map<String, String>> deliverResult(
            @PathVariable String type,
            @PathVariable String loanId,
            @RequestBody WebhookResultRequest request
    ) {
        if (!VALID_TYPES.contains(type)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown verification type '%s' — expected one of %s"
                            .formatted(type, VALID_TYPES)));
        }

        var details = request.details() != null
                ? request.details()
                : "%s verification delivered via webhook".formatted(type);
        var result = new VerificationResult(loanId, type, request.approved(), details);

        messaging.publishResult(result);
        logger.info("Webhook delivered {} verification result for loan {} (approved={})",
                type, loanId, request.approved());

        return ResponseEntity.accepted().body(Map.of(
                "loanId", loanId,
                "type", type,
                "signal", "verification.result",
                "deliveredVia", "loans.verification.results"));
    }
}
