package com.brimap.intake.mail;

import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** In-memory MIME messages: no mail server needed. */
class MailParserTest {
    private static final Session SESSION = Session.getInstance(new Properties());

    private static MimeBodyPart file(String name, byte[] bytes, String type, String disposition) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(bytes, type)));
        p.setFileName(name);
        p.setDisposition(disposition);
        return p;
    }

    private static MimeMessage invoiceEmail() throws Exception {
        MimeMessage m = new MimeMessage(SESSION);
        m.setFrom(new InternetAddress("ar@keystone.example", "Keystone Racking"));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("ap@harborline-logistics.example"));
        m.setSubject("Invoice KE26-1198 attached");
        m.setHeader("Message-ID", "<abc123@keystone.example>");
        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Please find attached our invoice KE26-1198.", "UTF-8");
        mp.addBodyPart(text);
        mp.addBodyPart(file("KE26-1198.pdf", "%PDF-1.4 fake".getBytes(StandardCharsets.US_ASCII), "application/pdf", Part.ATTACHMENT));
        mp.addBodyPart(file("logo.png", new byte[]{1, 2, 3}, "image/png", Part.INLINE));
        mp.addBodyPart(file("terms.docx", new byte[]{4, 5}, "application/octet-stream", Part.ATTACHMENT));
        m.setContent(mp);
        m.saveChanges();
        m.setHeader("Message-ID", "<abc123@keystone.example>"); // saveChanges() replaces it
        return roundTrip(m);
    }

    /** Serialize and re-read, so the parser sees raw MIME bytes exactly as an IMAP server would deliver them. */
    private static MimeMessage roundTrip(MimeMessage m) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        m.writeTo(out);
        return new MimeMessage(SESSION, new java.io.ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void readsSenderSubjectBodyAndPdfAttachment() throws Exception {
        var p = MailParser.parse(invoiceEmail());
        assertThat(p.fromAddress()).isEqualTo("ar@keystone.example");
        assertThat(p.fromName()).isEqualTo("Keystone Racking");
        assertThat(p.toAddress()).isEqualTo("ap@harborline-logistics.example");
        assertThat(p.subject()).isEqualTo("Invoice KE26-1198 attached");
        assertThat(p.body()).isEqualTo("Please find attached our invoice KE26-1198.");
        assertThat(p.messageId()).isEqualTo("<abc123@keystone.example>");
        assertThat(p.attachments()).extracting(MailParser.Attachment::filename).containsExactly("KE26-1198.pdf");
        assertThat(new String(p.attachments().get(0).bytes(), StandardCharsets.US_ASCII)).startsWith("%PDF");
    }

    @Test
    void skipsInlineImagesAndListsUnsupportedFiles() throws Exception {
        var p = MailParser.parse(invoiceEmail());
        assertThat(p.attachments()).noneMatch(a -> a.filename().equals("logo.png"));
        assertThat(p.skipped()).containsExactly("terms.docx (not a PDF or image)");
    }

    @Test
    void htmlOnlyBodyBecomesPlainText() throws Exception {
        MimeMessage m = new MimeMessage(SESSION);
        m.setFrom(new InternetAddress("billing@vendor.example"));
        m.setSubject("Facture 4443");
        m.setContent("<html><style>p{color:red}</style><p>Bonjour,</p><p>Voici la facture&nbsp;4443.</p></html>", "text/html; charset=UTF-8");
        m.saveChanges();
        var p = MailParser.parse(m);
        assertThat(p.body()).contains("Bonjour,").contains("Voici la facture 4443.").doesNotContain("<p>").doesNotContain("color");
        assertThat(p.attachments()).isEmpty();
    }

    @Test
    void noAttachmentEmailHasEmptyAttachmentList() throws Exception {
        MimeMessage m = new MimeMessage(SESSION);
        m.setFrom(new InternetAddress("ap@brightmore.example"));
        m.setSubject("Invoice attached");
        m.setText("Please find our October invoice attached.");
        m.saveChanges();
        assertThat(MailParser.parse(m).attachments()).isEmpty();
    }

    @Test
    void fileNamesCannotEscapeTheFolder() {
        assertThat(MailParser.safeFilename("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(MailParser.safeFilename("..\\..\\win.ini")).isEqualTo("win.ini");
        assertThat(MailParser.safeFilename(".hidden.pdf")).isEqualTo("hidden.pdf");
        assertThat(MailParser.safeFilename("fac:ture?.pdf")).isEqualTo("fac_ture_.pdf");
        assertThat(MailParser.safeFilename("")).isEqualTo("attachment");
    }

    @Test
    void sameMessageIdGivesSameEmailId() {
        assertThat(MailIntakeService.emailId("<abc123@keystone.example>"))
                .isEqualTo(MailIntakeService.emailId("<abc123@keystone.example>"))
                .startsWith("IM-").hasSize(15);
        assertThat(MailIntakeService.emailId("<a@x>")).isNotEqualTo(MailIntakeService.emailId("<b@x>"));
    }

    @Test
    void duplicateAttachmentNamesAreNumbered() {
        Set<String> used = new HashSet<>();
        assertThat(MailIntakeService.unique("invoice.pdf", used)).isEqualTo("invoice.pdf");
        assertThat(MailIntakeService.unique("Invoice.pdf", used)).isEqualTo("Invoice-2.pdf");
        assertThat(MailIntakeService.unique("invoice.pdf", used)).isEqualTo("invoice-3.pdf");
    }
}
