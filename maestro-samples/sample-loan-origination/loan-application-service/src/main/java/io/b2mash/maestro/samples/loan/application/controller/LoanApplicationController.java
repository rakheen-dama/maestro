package io.b2mash.maestro.samples.loan.application.controller;

import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.samples.loan.application.domain.ApplicationStatusResponse;
import io.b2mash.maestro.samples.loan.application.domain.CreateApplicationRequest;
import io.b2mash.maestro.samples.loan.application.domain.CreateApplicationResponse;
import io.b2mash.maestro.samples.loan.application.domain.DocumentUploaded;
import io.b2mash.maestro.samples.loan.application.domain.LoanApplication;
import io.b2mash.maestro.samples.loan.application.domain.SignRequest;
import io.b2mash.maestro.samples.loan.application.domain.Signature;
import io.b2mash.maestro.samples.loan.application.domain.UploadDocumentRequest;
import io.b2mash.maestro.samples.loan.application.domain.WithdrawRequest;
import io.b2mash.maestro.samples.loan.application.domain.Withdrawal;
import io.b2mash.maestro.samples.loan.application.workflow.LoanApplicationWorkflow;
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
 * Thin REST facade over {@link MaestroClient} for the loan application
 * workflow. Signal endpoints work even before the workflow exists — signals
 * are persisted as orphans and adopted when the workflow starts (used by the
 * E2E out-of-order scenario).
 */
@RestController
@RequestMapping("/applications")
public class LoanApplicationController {

    private final MaestroClient maestro;
    private final WorkflowStore store;

    public LoanApplicationController(MaestroClient maestro, WorkflowStore store) {
        this.maestro = maestro;
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<CreateApplicationResponse> create(@RequestBody CreateApplicationRequest request) {
        var applicationId = request.applicationId() != null && !request.applicationId().isBlank()
                ? request.applicationId()
                : UUID.randomUUID().toString();

        var input = new LoanApplication(
                applicationId,
                request.borrowerIds(),
                request.amount(),
                request.income(),
                request.propertyValue(),
                request.requiredDocs());

        maestro.newWorkflow(LoanApplicationWorkflow.class,
                WorkflowOptions.builder()
                        .workflowId(workflowId(applicationId))
                        .build()
        ).startAsync(input);

        return ResponseEntity.accepted().body(new CreateApplicationResponse(applicationId));
    }

    @PostMapping("/{applicationId}/documents")
    public ResponseEntity<Void> uploadDocument(@PathVariable String applicationId,
                                               @RequestBody UploadDocumentRequest request) {
        maestro.getWorkflow(workflowId(applicationId))
                .signal("document.uploaded",
                        new DocumentUploaded(applicationId, request.docType(), request.uploadedBy()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{applicationId}/sign")
    public ResponseEntity<Void> sign(@PathVariable String applicationId,
                                     @RequestBody SignRequest request) {
        maestro.getWorkflow(workflowId(applicationId))
                .signal("package.signed", new Signature(applicationId, request.signerId()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{applicationId}/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable String applicationId,
                                         @RequestBody WithdrawRequest request) {
        maestro.getWorkflow(workflowId(applicationId))
                .signal("application.withdrawn", new Withdrawal(applicationId, request.reason()));
        return ResponseEntity.accepted().build();
    }

    /** Reads instance status + output straight from the workflow store. */
    @GetMapping("/{applicationId}")
    public ResponseEntity<ApplicationStatusResponse> status(@PathVariable String applicationId) {
        var wfId = workflowId(applicationId);
        return store.getInstance(wfId)
                .map(instance -> ResponseEntity.ok(new ApplicationStatusResponse(
                        applicationId, wfId, instance.status().name(), instance.output())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String workflowId(String applicationId) {
        return "loan-" + applicationId;
    }
}
