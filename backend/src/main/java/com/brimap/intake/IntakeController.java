package com.brimap.intake;

import com.brimap.auth.Auth;
import com.brimap.auth.CurrentUser;
import com.brimap.common.BusinessRuleException;
import com.brimap.common.NotFoundException;
import com.brimap.extraction.ExtractionService;
import com.brimap.intake.mail.MailIntakeService;
import com.brimap.model.EmailMessage;
import com.brimap.model.Invoice;
import com.brimap.model.InvoiceStatus;
import com.brimap.model.StoredDocument;
import com.brimap.repository.EmailMessageRepository;
import com.brimap.repository.StoredDocumentRepository;
import com.brimap.workflow.InvoiceAccess;
import com.brimap.workflow.InvoiceStateMachine;
import com.brimap.workflow.InvoiceWorkflowService;
import com.brimap.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class IntakeController {
    private final InboxService inbox;
    private final IntakePipeline pipeline;
    private final EmailMessageRepository emails;
    private final StoredDocumentRepository docs;
    private final InvoiceWorkflowService workflow;
    private final InvoiceAccess access;
    private final ExtractionService extraction;
    private final AppProperties props;
    private final com.brimap.repository.VendorRepository vendors;
    private final com.brimap.validation.RevalidationService revalidation;
    private final MailIntakeService mail;

    @PostMapping("/inbox/process")
    @PreAuthorize("hasAnyRole('CLK','ADM')")
    public Map<String, Object> processInbox() { return inbox.processAll(); }

    @PostMapping("/inbox/{id}/process")
    @PreAuthorize("hasAnyRole('CLK','ADM')")
    public EmailMessage processOne(@PathVariable String id) { return inbox.processSingle(id); }

    public record ReplyBody(String to, String subject, String body, boolean draft) {}

    @PostMapping("/inbox/{id}/reply")
    @PreAuthorize("hasAnyRole('CLK','ADM')")
    public Map<String, Object> reply(@PathVariable String id, @RequestBody ReplyBody b) {
        inbox.reply(id, b.to(), b.subject(), b.body(), b.draft());
        return Map.of("recorded", true, "draft", b.draft());
    }

    @GetMapping("/inbox/status")
    @PreAuthorize("hasAnyRole('CLK','ADM','AUD')")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>(inbox.status());
        m.put("mailEnabled", mail.enabled());
        m.put("mailbox", mail.mailbox());
        return m;
    }

    /** Pull new messages from the live AP mailbox (IMAP) into the inbox. Processing them is still "Process inbox". */
    @PostMapping("/inbox/fetch")
    @PreAuthorize("hasAnyRole('CLK','ADM')")
    public MailIntakeService.FetchResult fetchMail() { return mail.fetch(); }

    @GetMapping("/inbox")
    @PreAuthorize("hasAnyRole('CLK','ADM','AUD')")
    public List<EmailMessage> list() { return emails.findAllByOrderByReceivedAtAsc(); }

    /** Upload a PDF/image -> draft bill. Employees upload for their own department (they become the requester). */
    @PostMapping(value = "/invoices/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EMP','CLK','ADM')")
    public Invoice upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Empty file");
        CurrentUser u = Auth.require();
        Invoice inv = pipeline.ingest(file.getBytes(), Optional.ofNullable(file.getOriginalFilename()).orElse("upload"),
                new IntakePipeline.Source("UPLOAD", null, null, null, u.id()));
        revalidation.revalidateVendor(inv); // other open bills from this vendor may now be duplicates of / split with it
        return inv;
    }

    @PostMapping("/invoices/{id}/reextract")
    @PreAuthorize("hasRole('CLK')")
    public Invoice reextract(@PathVariable String id) {
        Invoice inv = workflow.load(id);
        if (inv.isHistorical() || inv.getDocumentId() == null) throw new BusinessRuleException(id + " has no document to re-read");
        if (!InvoiceStateMachine.editable(inv.getStatus()) || inv.getStatus() == InvoiceStatus.RECEIVED) {
            throw new BusinessRuleException("Re-extraction is only allowed before submission (status is " + inv.getStatus() + ")");
        }
        return pipeline.reextract(id);
    }

    /** The original PDF/image, same scope rules as the invoice. */
    @GetMapping("/invoices/{id}/document")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> document(@PathVariable String id) {
        Invoice inv = workflow.load(id);
        access.requireView(Auth.require(), inv);
        if (inv.getDocumentId() == null) throw new NotFoundException("Document for " + id);
        StoredDocument d = docs.findById(inv.getDocumentId()).orElseThrow(() -> new NotFoundException("Document"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + d.getFilename().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType(d.getContentType()))
                .body(new FileSystemResource(Path.of(d.getStoragePath())));
    }

    /**
     * Extraction-only endpoint for the accuracy eval (scripts/eval_extraction.py): reads a file from the dataset's
     * incoming_invoices folder without creating a bill.
     */
    @PostMapping("/extraction/eval")
    @PreAuthorize("hasAnyRole('CLK','ADM')")
    public Map<String, Object> evalExtract(@RequestParam String file) throws IOException {
        Path dir = Path.of(props.dataDir(), "incoming_invoices").toAbsolutePath().normalize();
        Path f = dir.resolve(file).normalize();
        if (!f.startsWith(dir) || !Files.isRegularFile(f)) throw new NotFoundException("File " + file);
        byte[] bytes = Files.readAllBytes(f);
        var out = extraction.extract(f, DocumentStore.contentType(file, bytes), DocumentStore.sha256(bytes));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", file);
        m.put("method", out.method());
        m.put("model", out.model());
        m.put("fromCache", out.fromCache());
        m.put("durationMs", out.durationMs());
        m.put("error", out.error());
        m.put("extracted", out.invoice());
        var x = out.invoice();
        if (x != null) {
            Map<String, Object> n = new LinkedHashMap<>();
            var match = com.brimap.extraction.VendorMatcher.match(vendors.findAll(), x.vendorGstNumber(), null, x.vendorName());
            n.put("vendorId", match.vendorId());
            n.put("vendorMatch", match.method());
            n.put("documentType", x.documentType());
            n.put("invoiceNumber", com.brimap.extraction.InvoiceNormalizer.blankToNull(x.invoiceNumber()));
            n.put("invoiceDate", String.valueOf(com.brimap.extraction.InvoiceNormalizer.date(x.invoiceDate())));
            n.put("dueDate", String.valueOf(com.brimap.extraction.InvoiceNormalizer.date(x.dueDate())));
            n.put("currency", x.currency());
            n.put("subtotal", String.valueOf(com.brimap.extraction.InvoiceNormalizer.money(x.subtotal())));
            n.put("total", String.valueOf(com.brimap.extraction.InvoiceNormalizer.money(x.total())));
            List<Map<String, String>> tx = new ArrayList<>();
            for (var t : Optional.ofNullable(x.taxes()).orElse(List.of())) {
                tx.add(Map.of("code", String.valueOf(com.brimap.extraction.InvoiceNormalizer.taxCode(t.label(), t.ratePercent())),
                        "amount", String.valueOf(com.brimap.extraction.InvoiceNormalizer.money(t.amount()))));
            }
            n.put("taxes", tx);
            n.put("lineCount", x.lines() == null ? 0 : x.lines().size());
            n.put("linesSum", String.valueOf(Optional.ofNullable(x.lines()).orElse(List.of()).stream()
                    .map(l -> com.brimap.extraction.InvoiceNormalizer.money(l.amount())).filter(Objects::nonNull)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)));
            n.put("gstNumber", com.brimap.extraction.VendorMatcher.canonicalGst(x.vendorGstNumber()));
            n.put("remitAccount", com.brimap.extraction.InvoiceNormalizer.blankToNull(com.brimap.extraction.InvoiceNormalizer.digits(x.remitAccount())));
            n.put("shipToProvince", com.brimap.extraction.InvoiceAssembler.shipToProvince(x.billToName() + "\n" + x.billToAddress()));
            n.put("poNumber", com.brimap.extraction.InvoiceNormalizer.blankToNull(x.poNumber()));
            m.put("normalized", n);
        }
        return m;
    }
}
