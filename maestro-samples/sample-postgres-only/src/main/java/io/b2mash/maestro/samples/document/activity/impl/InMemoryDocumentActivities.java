package io.b2mash.maestro.samples.document.activity.impl;

import io.b2mash.maestro.samples.document.activity.DocumentActivities;
import io.b2mash.maestro.samples.document.domain.DocumentInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link DocumentActivities} for demonstration purposes.
 *
 * <p>Stores documents in a {@link ConcurrentHashMap} and simulates latency on each
 * operation. In production, this would be backed by a document management system,
 * notification service, etc.
 */
@Component
public class InMemoryDocumentActivities implements DocumentActivities {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDocumentActivities.class);

    private static final List<String> REVIEWER_POOL = List.of(
        "reviewer-alice", "reviewer-bob", "reviewer-carol"
    );

    private final ConcurrentHashMap<String, DocumentRecord> documents = new ConcurrentHashMap<>();

    @Override
    public String validateAndStore(DocumentInput input) {
        simulateLatency();

        if (input.title() == null || input.title().isBlank()) {
            throw new IllegalArgumentException("Document title must not be blank");
        }
        if (input.content() == null || input.content().isBlank()) {
            throw new IllegalArgumentException("Document content must not be blank");
        }

        var documentId = UUID.randomUUID().toString();
        documents.put(documentId, new DocumentRecord(
            documentId, input.title(), input.content(), input.authorId(), "STORED"
        ));

        log.info("Validated and stored document: documentId={}, title='{}', authorId={}",
            documentId, input.title(), input.authorId());

        return documentId;
    }

    @Override
    public String assignReviewer(String documentId) {
        simulateLatency();

        // Simple round-robin based on document hash
        var index = Math.abs(documentId.hashCode()) % REVIEWER_POOL.size();
        var reviewerId = REVIEWER_POOL.get(index);

        log.info("Assigned reviewer: documentId={}, reviewerId={}", documentId, reviewerId);
        return reviewerId;
    }

    @Override
    public void publishDocument(String documentId) {
        simulateLatency();

        documents.computeIfPresent(documentId, (id, doc) ->
            new DocumentRecord(doc.id(), doc.title(), doc.content(), doc.authorId(), "PUBLISHED"));

        log.info("Published document: documentId={}", documentId);
    }

    @Override
    public void notifyRejection(String documentId, String authorId, String reason) {
        simulateLatency();

        documents.computeIfPresent(documentId, (id, doc) ->
            new DocumentRecord(doc.id(), doc.title(), doc.content(), doc.authorId(), "REJECTED"));

        log.info("Notified author of rejection: documentId={}, authorId={}, reason='{}'",
            documentId, authorId, reason);
    }

    @Override
    public void archiveAuditTrail(String documentId, String finalStatus) {
        simulateLatency();

        log.info("Archived audit trail: documentId={}, finalStatus={}", documentId, finalStatus);
    }

    private static void simulateLatency() {
        try {
            Thread.sleep(100 + (long) (Math.random() * 200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record DocumentRecord(
        String id,
        String title,
        String content,
        String authorId,
        String status
    ) {}
}
