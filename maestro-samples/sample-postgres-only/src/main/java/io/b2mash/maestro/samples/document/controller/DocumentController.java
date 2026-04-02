package io.b2mash.maestro.samples.document.controller;

import io.b2mash.maestro.samples.document.domain.DocumentInput;
import io.b2mash.maestro.samples.document.domain.DocumentStatus;
import io.b2mash.maestro.samples.document.domain.ReviewDecision;
import io.b2mash.maestro.samples.document.domain.ReviewRequest;
import io.b2mash.maestro.samples.document.domain.SubmitDocumentRequest;
import io.b2mash.maestro.samples.document.domain.SubmitDocumentResponse;
import io.b2mash.maestro.samples.document.workflow.DocumentApprovalWorkflow;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for the document approval workflow.
 *
 * <p>Provides endpoints to submit documents, check workflow status, and deliver
 * review decisions as Maestro signals.
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final MaestroClient maestro;

    public DocumentController(MaestroClient maestro) {
        this.maestro = maestro;
    }

    /**
     * Submit a document for approval. Starts a new durable workflow.
     */
    @PostMapping
    public ResponseEntity<SubmitDocumentResponse> submit(@RequestBody SubmitDocumentRequest request) {
        var documentId = UUID.randomUUID().toString();

        var input = new DocumentInput(request.title(), request.content(), request.authorId());

        maestro.newWorkflow(DocumentApprovalWorkflow.class,
            WorkflowOptions.builder()
                .workflowId("doc-" + documentId)
                .build()
        ).startAsync(input);

        return ResponseEntity.accepted().body(new SubmitDocumentResponse(documentId));
    }

    /**
     * Check the current status of a document approval workflow.
     */
    @GetMapping("/{documentId}/status")
    public DocumentStatus getStatus(@PathVariable String documentId) {
        return maestro.getWorkflow("doc-" + documentId)
            .query("getStatus", DocumentStatus.class);
    }

    /**
     * Submit a review decision. This delivers a signal to the running workflow,
     * causing it to resume from the {@code awaitSignal} call.
     */
    @PostMapping("/{documentId}/review")
    public ResponseEntity<Void> review(
            @PathVariable String documentId,
            @RequestBody ReviewRequest request) {
        maestro.getWorkflow("doc-" + documentId)
            .signal("review.decision",
                new ReviewDecision(request.approved(), request.reviewerId(), request.comments()));
        return ResponseEntity.ok().build();
    }
}
