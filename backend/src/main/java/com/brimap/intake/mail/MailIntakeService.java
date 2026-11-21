package com.brimap.intake.mail;

import com.brimap.audit.AuditService;
import com.brimap.common.BusinessRuleException;
import com.brimap.config.AppProperties;
import com.brimap.config.MailProperties;
import com.brimap.model.EmailMessage;
import com.brimap.repository.EmailMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Live mailbox -> AP inbox. Each unseen message on the server becomes an {@link EmailMessage} row exactly like the
 * dataset's emails.json entries, with its attachments saved under storage/mail/&lt;email id&gt;/. Fetching only fills the
 * inbox: classification and bill creation still happen when someone clicks "Process inbox", so the LLM cost and the
 * audit trail stay with a named user.
 */
@Service
@Slf4j
public class MailIntakeService {
    private final ImapMailbox mailbox;
    private final MailProperties mail;
    private final AppProperties app;
    private final EmailMessageRepository emails;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public MailIntakeService(ImapMailbox mailbox, MailProperties mail, AppProperties app, EmailMessageRepository emails,
                             AuditService audit, PlatformTransactionManager tm) {
        this.mailbox = mailbox; this.mail = mail; this.app = app; this.emails = emails; this.audit = audit;
        this.tx = new TransactionTemplate(tm);
    }

    public record FetchResult(int read, int saved, int alreadyHad, List<String> errors) {}

    public boolean enabled() { return mail.configured(); }

    public String mailbox() { return enabled() ? mail.username() + " (" + mail.folderOrDefault() + ")" : null; }

    /** Pull every unseen message now. Synchronized: the timer and the button never fetch at the same time. */
    public synchronized FetchResult fetch() {
        if (!enabled()) throw new BusinessRuleException("No mail server configured (set MAIL_ENABLED, MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD)");
        int[] saved = {0}, dup = {0};
        List<String> errors = new ArrayList<>();
        int read;
        try {
            read = mailbox.readUnseen(m -> {
                try {
                    MailParser.ParsedEmail p = MailParser.parse(m);
                    String id = emailId(p.messageId());
                    if (emails.existsById(id)) { dup[0]++; return true; }
                    save(id, p);
                    saved[0]++;
                    return true;
                } catch (Exception ex) { // one bad message must not stop the rest; it stays unseen and is retried
                    log.warn("Could not read a message from the mail server: {}", ex.getMessage());
                    errors.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    return false;
                }
            });
        } catch (jakarta.mail.AuthenticationFailedException ex) {
            throw new BusinessRuleException("Mail server rejected the login for " + mail.username() + " (check MAIL_PASSWORD; Gmail and Outlook need an app password)");
        } catch (jakarta.mail.MessagingException ex) {
            throw new BusinessRuleException("Cannot reach mail server " + mail.host() + ": " + ex.getMessage());
        }
        FetchResult r = new FetchResult(read, saved[0], dup[0], List.copyOf(errors));
        tx.executeWithoutResult(s -> audit.record("MAILBOX_FETCHED", "inbox", mail.username(), null, null,
                r.saved() + " new emails from " + mail.host() + ", " + r.alreadyHad() + " already in the inbox, " + r.errors().size() + " unreadable"));
        return r;
    }

    /** Optional timer. Runs every app.mail.poll-ms but does nothing unless MAIL_AUTO_POLL=true. */
    @Scheduled(initialDelayString = "${app.mail.poll-ms:60000}", fixedDelayString = "${app.mail.poll-ms:60000}")
    public void poll() {
        if (!enabled() || !mail.autoPoll()) return;
        try {
            FetchResult r = fetch();
            if (r.saved() > 0) log.info("Mailbox poll: {} new emails", r.saved());
        } catch (RuntimeException ex) {
            log.warn("Mailbox poll failed: {}", ex.getMessage());
        }
    }

    private void save(String id, MailParser.ParsedEmail p) throws IOException {
        Path dir = Path.of(app.storageDir(), "mail", id).toAbsolutePath().normalize();
        List<String> names = new ArrayList<>();
        if (!p.attachments().isEmpty()) {
            Files.createDirectories(dir);
            Set<String> used = new HashSet<>();
            for (MailParser.Attachment a : p.attachments()) {
                String name = unique(a.filename(), used);
                Path f = dir.resolve(name).normalize();
                if (!f.startsWith(dir)) throw new IOException("Bad attachment name " + a.filename());
                Files.write(f, a.bytes());
                names.add(name);
            }
        }
        String body = p.body() == null ? "" : p.body();
        if (!p.skipped().isEmpty()) body += "\n\n[Not imported: " + String.join(", ", p.skipped()) + "]";
        EmailMessage e = new EmailMessage();
        e.setId(id);
        e.setMessageId(p.messageId());
        e.setFromName(p.fromName());
        e.setFromAddress(p.fromAddress());
        e.setToAddress(p.toAddress());
        e.setSubject(p.subject());
        e.setReceivedAt(p.receivedAt());
        e.setBody(body);
        e.setAttachments(String.join(",", names));
        e.setAttachmentDir(names.isEmpty() ? null : dir.toString());
        tx.executeWithoutResult(s -> {
            emails.save(e);
            audit.record("EMAIL_RECEIVED", "email", id, null, Map.of("from", String.valueOf(p.fromAddress()),
                    "attachments", names.size()), "From mail server: " + p.subject());
        });
    }

    /** Stable id from the Message-ID header, so the same message fetched twice is the same row. */
    static String emailId(String messageId) {
        return "IM-" + MailParser.sha256(messageId).substring(0, 12).toUpperCase(Locale.ROOT);
    }

    /** Two attachments called "invoice.pdf" in one email become invoice.pdf and invoice-2.pdf. */
    static String unique(String name, Set<String> used) {
        String n = name;
        int dot = name.lastIndexOf('.');
        for (int i = 2; !used.add(n.toLowerCase(Locale.ROOT)); i++) {
            n = dot > 0 ? name.substring(0, dot) + "-" + i + name.substring(dot) : name + "-" + i;
        }
        return n;
    }
}
