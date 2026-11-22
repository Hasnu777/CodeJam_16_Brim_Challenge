package com.brimap.intake;

import com.brimap.common.NotFoundException;
import com.brimap.config.AppProperties;
import com.brimap.model.EmailMessage;
import com.brimap.model.Invoice;
import com.brimap.repository.AuditEventRepository;
import com.brimap.repository.EmailMessageRepository;
import com.brimap.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Read-only view of one inbox email, before or after "Process inbox": the message as received (headers, body,
 * attachments you can open), and once processed, how it was classified, the bills it produced and its audit trail.
 */
@RestController
@RequestMapping("/api/inbox")
@RequiredArgsConstructor
public class InboxViewController {
    private final EmailMessageRepository emails;
    private final InvoiceRepository invoices;
    private final AuditEventRepository auditEvents;
    private final AppProperties props;

    public record AttachmentInfo(String name, boolean available, String contentType, Long sizeBytes) {}

    public record LinkedInvoice(String id, String status, String vendorId, String vendorInvoiceNumber, String total, String currency) {}

    public record HistoryItem(String ts, String actor, String action, String detail) {}

    public record EmailView(EmailMessage email, boolean processed, List<AttachmentInfo> attachments,
                            List<LinkedInvoice> linkedInvoices, List<HistoryItem> history) {}

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLK','ADM','AUD')")
    public EmailView view(@PathVariable String id) {
        EmailMessage e = emails.findById(id).orElseThrow(() -> new NotFoundException("Email " + id));
        List<AttachmentInfo> att = new ArrayList<>();
        for (String name : attachmentNames(e)) {
            Path f = attachmentPath(e, name);
            boolean ok = Files.isRegularFile(f);
            att.add(new AttachmentInfo(name, ok, ok ? contentType(f, name) : null, ok ? size(f) : null));
        }
        List<LinkedInvoice> linked = new ArrayList<>();
        for (String invId : split(e.getLinkedInvoiceIds())) {
            invoices.findById(invId).ifPresent(i -> linked.add(linked(i)));
        }
        List<HistoryItem> history = auditEvents.findByEntityAndEntityIdOrderByIdAsc("email", id).stream()
                .map(a -> new HistoryItem(String.valueOf(a.getTs()), a.getActor(), a.getAction(), a.getDetail())).toList();
        return new EmailView(e, e.getProcessedAt() != null, att, linked, history);
    }

    /** Open one attachment of an email. Only names listed on the email are served, so no path can be smuggled in. */
    @GetMapping("/{id}/attachments/{name}")
    @PreAuthorize("hasAnyRole('CLK','ADM','AUD')")
    public ResponseEntity<Resource> attachment(@PathVariable String id, @PathVariable String name) {
        EmailMessage e = emails.findById(id).orElseThrow(() -> new NotFoundException("Email " + id));
        if (!attachmentNames(e).contains(name)) throw new NotFoundException("Attachment " + name + " on " + id);
        Path f = attachmentPath(e, name);
        if (!Files.isRegularFile(f)) throw new NotFoundException("File " + name + " is missing");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name.replace("\"", "") + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(contentType(f, name)))
                .body(new FileSystemResource(f));
    }

    static List<String> attachmentNames(EmailMessage e) {
        return split(e.getAttachments());
    }

    private Path attachmentPath(EmailMessage e, String name) {
        Path dir = (e.getAttachmentDir() != null ? Path.of(e.getAttachmentDir()) : Path.of(props.dataDir(), "incoming_invoices"))
                .toAbsolutePath().normalize();
        Path f = dir.resolve(name).normalize();
        if (!f.startsWith(dir)) throw new NotFoundException("Attachment " + name);
        return f;
    }

    /** Sniff the first bytes like DocumentStore does; anything unrecognised downloads as binary, never renders. */
    private static String contentType(Path f, String name) {
        try (InputStream in = Files.newInputStream(f)) {
            return DocumentStore.contentType(name, in.readNBytes(8));
        } catch (IOException ex) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private static Long size(Path f) {
        try { return Files.size(f); } catch (IOException ex) { return null; }
    }

    private static LinkedInvoice linked(Invoice i) {
        return new LinkedInvoice(i.getId(), String.valueOf(i.getStatus()), i.getVendorId(), i.getVendorInvoiceNumber(),
                i.getTotal() == null ? null : i.getTotal().toPlainString(), i.getCurrency());
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
