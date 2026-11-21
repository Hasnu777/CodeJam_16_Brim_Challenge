package com.brimap.intake;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.common.BusinessRuleException;
import com.brimap.config.AppProperties;
import com.brimap.extraction.LlmExtractor;
import com.brimap.extraction.VendorMatcher;
import com.brimap.duplicates.InvoiceNumbers;
import com.brimap.model.*;
import com.brimap.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Process inbox": classify every unprocessed email, turn invoice attachments into draft bills, and send
 * everything else to Needs attention with a reason. Runs in the background (4 workers) because each attachment
 * is an LLM call; the UI polls {@link #status()}.
 */
@Service
@Slf4j
public class InboxService {
    private final EmailMessageRepository emails;
    private final IntakePipeline pipeline;
    private final LlmExtractor llm;
    private final AppProperties props;
    private final AuditService audit;
    private final VendorRepository vendors;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final TransactionTemplate tx;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    private volatile Job job;

    public InboxService(EmailMessageRepository emails, IntakePipeline pipeline, LlmExtractor llm, AppProperties props, AuditService audit,
                        VendorRepository vendors, InvoiceRepository invoices, PaymentRepository payments, PlatformTransactionManager tm) {
        this.emails = emails; this.pipeline = pipeline; this.llm = llm; this.props = props; this.audit = audit;
        this.vendors = vendors; this.invoices = invoices; this.payments = payments; this.tx = new TransactionTemplate(tm);
    }

    public static final class Job {
        public final Instant startedAt = Instant.now();
        public final String startedBy;
        public final int total;
        public final AtomicInteger done = new AtomicInteger();
        public final AtomicInteger invoicesCreated = new AtomicInteger();
        public final AtomicInteger needsAttention = new AtomicInteger();
        public final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        public volatile Instant finishedAt;

        Job(String by, int total) { this.startedBy = by; this.total = total; }

        public boolean running() { return finishedAt == null; }
    }

    public Map<String, Object> status() {
        Job j = job;
        Map<String, Object> m = new LinkedHashMap<>();
        long unprocessed = emails.findAll().stream().filter(e -> e.getProcessedAt() == null).count();
        m.put("unprocessed", unprocessed);
        m.put("llmEnabled", llm.enabled());
        m.put("llmModel", llm.enabled() ? llm.model() : null);
        if (j != null) {
            m.put("running", j.running());
            m.put("startedBy", j.startedBy);
            m.put("startedAt", j.startedAt.toString());
            m.put("finishedAt", j.finishedAt == null ? null : j.finishedAt.toString());
            m.put("total", j.total);
            m.put("done", j.done.get());
            m.put("invoicesCreated", j.invoicesCreated.get());
            m.put("needsAttention", j.needsAttention.get());
            m.put("errors", List.copyOf(j.errors));
        } else {
            m.put("running", false);
        }
        return m;
    }

    /** Start processing every unprocessed email in the background. */
    public synchronized Map<String, Object> processAll() {
        if (job != null && job.running()) throw new BusinessRuleException("Inbox processing is already running");
        List<EmailMessage> todo = emails.findAllByOrderByReceivedAtAsc().stream().filter(e -> e.getProcessedAt() == null).toList();
        Job j = new Job(Auth.actorId(), todo.size());
        job = j;
        tx.executeWithoutResult(s -> audit.record("INBOX_PROCESS_STARTED", "inbox", "emails.json", null, null, todo.size() + " unprocessed emails"));
        ExecutorService exec = new DelegatingSecurityContextExecutorService(pool); // workers act as the user who clicked
        List<Future<?>> futures = new ArrayList<>();
        for (EmailMessage e : todo) {
            futures.add(exec.submit(() -> {
                try {
                    processOne(e.getId(), j);
                } catch (RuntimeException ex) {
                    log.error("Email {} failed", e.getId(), ex);
                    j.errors.add(e.getId() + ": " + ex.getMessage());
                } finally {
                    j.done.incrementAndGet();
                }
            }));
        }
        exec.submit(() -> {
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) { /* counted above */ }
            }
            j.finishedAt = Instant.now();
            tx.executeWithoutResult(s -> audit.record("INBOX_PROCESS_FINISHED", "inbox", "emails.json", null, null,
                    j.invoicesCreated.get() + " bills created, " + j.needsAttention.get() + " need attention, " + j.errors.size() + " errors"));
        });
        return status();
    }

    /** Synchronous single-email processing (also used by tests and the async workers). */
    public void processOne(String emailId, Job j) {
        EmailMessage e = emails.findById(emailId).orElseThrow();
        if (e.getProcessedAt() != null) return;
        List<String> att = e.getAttachments() == null || e.getAttachments().isBlank() ? List.of() : List.of(e.getAttachments().split(","));
        EmailClassifier.Result r = EmailClassifier.classify(e.getSubject(), e.getBody(), att);
        String by = "KEYWORD";
        if (r.kind() == EmailClassifier.Kind.UNKNOWN) {
            var lr = llm.classifyEmail(e.getFromAddress(), e.getSubject(), e.getBody(), !att.isEmpty());
            if (lr.isPresent()) {
                by = "LLM";
                r = fromLlm(lr.get(), r.referencedInvoiceNumber());
            } else {
                r = new EmailClassifier.Result(EmailClassifier.Kind.NOT_INVOICE, "E04", "No attachment and no invoice keywords: ignored", null);
            }
        }
        List<String> created = new ArrayList<>();
        String reason = r.reason();
        if (r.kind() == EmailClassifier.Kind.INVOICE) {
            for (String a : att) {
                Path f = Path.of(props.dataDir(), "incoming_invoices", a.trim());
                try {
                    byte[] bytes = Files.readAllBytes(f);
                    Invoice inv = pipeline.ingest(bytes, a.trim(), new IntakePipeline.Source("EMAIL", e.getId(), e.getFromAddress(),
                            e.getReceivedAt() == null ? null : e.getReceivedAt().toLocalDate(), null));
                    created.add(inv.getId());
                    if (j != null) j.invoicesCreated.incrementAndGet();
                } catch (java.io.IOException ex) {
                    reason += "; attachment " + a + " is missing";
                    if (j != null) j.errors.add(e.getId() + ": attachment " + a + " not found");
                }
            }
        } else if (r.kind() == EmailClassifier.Kind.PAYMENT_INQUIRY) {
            String link = linkInquiry(e.getFromAddress(), r.referencedInvoiceNumber());
            if (link != null) {
                created.add(link.substring(0, link.indexOf('|')));
                reason += ". " + link.substring(link.indexOf('|') + 1);
            } else {
                reason += ". No matching invoice found in our records";
            }
        }
        EmailClassifier.Result fr = r;
        String fby = by, freason = reason;
        tx.executeWithoutResult(s -> {
            EmailMessage m = emails.findById(emailId).orElseThrow();
            m.setClassification(fr.kind().name());
            m.setIntakeCode(fr.code());
            m.setClassifiedBy(fby);
            m.setReason(freason);
            m.setNeedsAttention(fr.kind() == EmailClassifier.Kind.NO_ATTACHMENT || fr.kind() == EmailClassifier.Kind.PAYMENT_INQUIRY);
            m.setLinkedInvoiceIds(created.isEmpty() ? null : String.join(",", created));
            m.setProcessedAt(Instant.now());
            emails.save(m);
            audit.record("EMAIL_CLASSIFIED", "email", emailId, null,
                    Map.of("classification", fr.kind().name(), "code", String.valueOf(fr.code()), "by", fby), freason);
        });
        if (j != null && fr.kind() != EmailClassifier.Kind.INVOICE) j.needsAttention.incrementAndGet();
    }

    private EmailClassifier.Result fromLlm(LlmExtractor.EmailClass c, String ref) {
        String why = "LLM: " + c.reason();
        return switch (c.category() == null ? "" : c.category().trim().toLowerCase(Locale.ROOT)) {
            case "no_attachment" -> new EmailClassifier.Result(EmailClassifier.Kind.NO_ATTACHMENT, "E01", why, ref);
            case "payment_inquiry" -> new EmailClassifier.Result(EmailClassifier.Kind.PAYMENT_INQUIRY, "E02", why, ref);
            case "statement" -> new EmailClassifier.Result(EmailClassifier.Kind.STATEMENT, "E03", why, null);
            default -> new EmailClassifier.Result(EmailClassifier.Kind.NOT_INVOICE, "E04", why, null);
        };
    }

    /** E02: find the invoice the vendor asks about and report its payment status. Returns "AP-id|sentence". */
    private String linkInquiry(String from, String number) {
        if (number == null) return null;
        String domain = VendorMatcher.domain(from);
        Set<String> vendorIds = new HashSet<>();
        vendors.findAll().stream().filter(v -> domain != null && domain.equals(v.emailDomain())).forEach(v -> vendorIds.add(v.getId()));
        String norm = InvoiceNumbers.normalize(number);
        return invoices.findAll().stream()
                .filter(i -> norm.equals(i.getNormalizedInvoiceNumber()) && (vendorIds.isEmpty() || vendorIds.contains(i.getVendorId())))
                .findFirst().map(i -> {
                    String pay = payments.findByInvoiceId(i.getId()).stream().findFirst()
                            .map(p -> "payment " + p.getId() + " is " + p.getStatus()
                                    + (p.getPaidDate() != null ? " (paid " + p.getPaidDate() + ", ref " + p.getReference() + ")" : ""))
                            .orElse("no payment yet");
                    return i.getId() + "|Matched " + i.getId() + " (status " + i.getStatus() + "), " + pay;
                }).orElse(null);
    }
}
