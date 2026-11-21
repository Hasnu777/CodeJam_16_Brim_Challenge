package com.brimap.intake;

import com.brimap.config.AppClock;
import com.brimap.extraction.ExtractionService;
import com.brimap.extraction.InvoiceAssembler;
import com.brimap.model.*;
import com.brimap.repository.*;
import com.brimap.workflow.IdGenerator;
import com.brimap.workflow.InvoiceWorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Document -> draft bill. RECEIVED (stored) -> EXTRACTED (read + normalized) -> processing steps (coding,
 * validation) -> IN_REVIEW for the AP clerk. The slow LLM call runs outside any DB transaction; each state
 * change runs in its own transaction through InvoiceWorkflowService, so every step is audited.
 */
@Service
@Slf4j
public class IntakePipeline {
    private final DocumentStore store;
    private final ExtractionService extraction;
    private final InvoiceAssembler assembler;
    private final InvoiceWorkflowService workflow;
    private final IdGenerator ids;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final InvoiceTaxRepository taxes;
    private final ExtractionRepository extractions;
    private final StoredDocumentRepository docs;
    private final AppUserRepository users;
    private final List<InvoiceProcessingStep> steps;
    private final AppClock clock;
    private final TransactionTemplate tx;

    public IntakePipeline(DocumentStore store, ExtractionService extraction, InvoiceAssembler assembler, InvoiceWorkflowService workflow,
                          IdGenerator ids, InvoiceRepository invoices, InvoiceLineRepository lines, InvoiceTaxRepository taxes,
                          ExtractionRepository extractions, StoredDocumentRepository docs, AppUserRepository users,
                          List<InvoiceProcessingStep> steps, AppClock clock, PlatformTransactionManager tm) {
        this.store = store; this.extraction = extraction; this.assembler = assembler; this.workflow = workflow; this.ids = ids;
        this.invoices = invoices; this.lines = lines; this.taxes = taxes; this.extractions = extractions; this.docs = docs;
        this.users = users; this.clock = clock; this.tx = new TransactionTemplate(tm);
        this.steps = steps.stream().sorted(Comparator.comparingInt(InvoiceProcessingStep::order)).toList();
    }

    public record Source(String kind, String emailId, String senderEmail, LocalDate receivedDate, String uploaderId) {}

    /** Full intake of one file. Never throws on bad documents: they become manual-entry drafts. */
    public Invoice ingest(byte[] bytes, String filename, Source src) {
        Invoice created = tx.execute(s -> {
            StoredDocument doc = store.store(bytes, filename, src.emailId(), src.uploaderId());
            Invoice inv = new Invoice();
            inv.setId(ids.invoiceId());
            inv.setSource(src.kind());
            inv.setEmailId(src.emailId());
            inv.setSenderEmail(src.senderEmail());
            inv.setReceivedDate(src.receivedDate() != null ? src.receivedDate() : clock.today());
            inv.setDocumentId(doc.getId());
            inv.setVendorNameRaw(filename);
            // An employee uploading their own bill is its requester, in their own department.
            if (src.uploaderId() != null) users.findById(src.uploaderId()).filter(u -> u.getRole() == Role.EMP).ifPresent(u -> {
                inv.setRequesterUserId(u.getId());
                inv.setDepartmentId(u.getDepartmentId());
            });
            return workflow.create(inv, (src.emailId() != null ? "From email " + src.emailId() : "Uploaded") + ": " + filename);
        });
        return extractAndProcess(created.getId(), false);
    }

    /** Re-run extraction on an invoice's stored document (clerk action), then re-run all processing steps. */
    public Invoice reextract(String invoiceId) {
        return extractAndProcess(invoiceId, true);
    }

    private Invoice extractAndProcess(String invoiceId, boolean again) {
        Invoice inv0 = invoices.findById(invoiceId).orElseThrow();
        StoredDocument doc = docs.findById(inv0.getDocumentId()).orElseThrow();
        ExtractionService.Outcome out = extraction.extract(store.path(doc), doc.getContentType(), again ? null : doc.getSha256());

        return tx.execute(s -> {
            Invoice inv = invoices.findById(invoiceId).orElseThrow();
            lines.deleteByInvoiceId(invoiceId);
            taxes.deleteByInvoiceId(invoiceId);
            InvoiceAssembler.Assembled a = assembler.apply(inv, out.invoice(), inv.getSenderEmail());
            a.lines().forEach(l -> { l.setInvoiceId(invoiceId); lines.save(l); });
            a.taxes().forEach(t -> { t.setInvoiceId(invoiceId); taxes.save(t); });

            Extraction e = new Extraction();
            e.setDocumentId(doc.getId());
            e.setInvoiceId(invoiceId);
            e.setMethod(out.method());
            e.setModel(out.model());
            e.setRawText(out.rawText());
            e.setRawJson(out.rawJson());
            e.setError(out.error());
            e.setFromCache(out.fromCache());
            e.setDurationMs(out.durationMs());
            e.setConfidenceJson(out.invoice() == null || out.invoice().confidence() == null ? null
                    : com.brimap.common.Json.write(out.invoice().confidence()));
            e.setCreatedAt(clock.now());
            extractions.save(e);

            String detail = out.manual()
                    ? "Manual-entry draft: " + out.error()
                    : "Read by " + out.method() + (out.model() == null ? "" : " (" + out.model() + (out.fromCache() ? ", cached" : "") + ")")
                    + "; vendor match " + a.vendorMatch().method() + " - " + a.vendorMatch().detail()
                    + (a.lowConfidence().isEmpty() ? "" : "; check: " + String.join(", ", a.lowConfidence()));
            InvoiceStatus target = InvoiceStatus.EXTRACTED;
            inv = workflow.transition(inv, target, again ? "INVOICE_REEXTRACTED" : "INVOICE_EXTRACTED", detail);
            return runStepsAndQueue(inv);
        });
    }

    /** Run coding/validation steps and hand to the clerk. Also used after a clerk edits fields. */
    public Invoice runStepsAndQueue(Invoice inv) {
        for (InvoiceProcessingStep step : steps) {
            try {
                step.process(inv);
            } catch (RuntimeException ex) {
                log.error("Processing step {} failed on {}", step.getClass().getSimpleName(), inv.getId(), ex);
                throw ex;
            }
        }
        Invoice fresh = invoices.findById(inv.getId()).orElseThrow();
        if (fresh.getStatus() == InvoiceStatus.EXTRACTED) {
            fresh = workflow.transition(fresh, InvoiceStatus.IN_REVIEW, "QUEUED_FOR_REVIEW", "Ready for AP clerk review");
        }
        return fresh;
    }

    public Map<String, Object> summary(Invoice i) {
        return Map.of("id", i.getId(), "status", i.getStatus(), "vendorId", String.valueOf(i.getVendorId()),
                "invoiceNumber", String.valueOf(i.getVendorInvoiceNumber()), "total", String.valueOf(i.getTotal()));
    }
}
