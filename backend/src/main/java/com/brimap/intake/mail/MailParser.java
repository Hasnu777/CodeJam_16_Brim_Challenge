package com.brimap.intake.mail;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Turns one MIME message from the mail server into the same shape as an emails.json entry: sender, subject, plain
 * body, and its PDF/PNG/JPEG attachments as bytes. Pure (no server, no DB), so it is unit-tested with in-memory
 * messages. Untrusted input: file names are sanitized, sizes capped, inline images (signatures, logos) skipped.
 */
public final class MailParser {
    private MailParser() {}

    static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024; // same limit as manual upload
    private static final Set<String> ACCEPTED_EXT = Set.of(".pdf", ".png", ".jpg", ".jpeg");

    public record Attachment(String filename, byte[] bytes) {}

    public record ParsedEmail(String messageId, String fromName, String fromAddress, String toAddress, String subject,
                              LocalDateTime receivedAt, String body, List<Attachment> attachments, List<String> skipped) {}

    public static ParsedEmail parse(Message m) throws MessagingException, IOException {
        String fromName = null, fromAddress = null;
        Address[] from = m.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            fromName = ia.getPersonal();
            fromAddress = ia.getAddress() == null ? null : ia.getAddress().toLowerCase(Locale.ROOT);
        }
        Address[] to = m.getRecipients(Message.RecipientType.TO);
        String toAddress = to != null && to.length > 0 && to[0] instanceof InternetAddress ta ? ta.getAddress() : null;
        Date when = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        LocalDateTime receivedAt = when == null ? null : LocalDateTime.ofInstant(when.toInstant(), ZoneOffset.UTC);

        StringBuilder plain = new StringBuilder(), html = new StringBuilder();
        List<Attachment> att = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        walk(m, plain, html, att, skipped);
        String body = plain.length() > 0 ? plain.toString().trim() : htmlToText(html.toString());

        String[] ids = m.getHeader("Message-ID");
        String messageId = ids != null && ids.length > 0 && !ids[0].isBlank() ? ids[0].trim()
                : "<" + sha256(fromAddress + "|" + receivedAt + "|" + m.getSubject()) + "@no-message-id>";
        return new ParsedEmail(messageId, fromName, fromAddress, toAddress, m.getSubject(), receivedAt, body, att, skipped);
    }

    private static void walk(Part p, StringBuilder plain, StringBuilder html, List<Attachment> att, List<String> skipped)
            throws MessagingException, IOException {
        String fileName = p.getFileName() == null ? null : MimeUtility.decodeText(p.getFileName());
        String disposition = p.getDisposition();
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i), plain, html, att, skipped);
        } else if (fileName != null || Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
            if (Part.INLINE.equalsIgnoreCase(disposition)) return; // logos and signature images inside the HTML body
            String name = safeFilename(fileName);
            if (!accepted(name)) { skipped.add(name + " (not a PDF or image)"); return; }
            byte[] bytes = readCapped(p.getInputStream());
            if (bytes == null) { skipped.add(name + " (larger than 20 MB)"); return; }
            att.add(new Attachment(name, bytes));
        } else if (p.isMimeType("text/plain")) {
            plain.append(p.getContent()).append('\n');
        } else if (p.isMimeType("text/html")) {
            html.append(p.getContent()).append('\n');
        }
    }

    /** Keep only the last path segment and safe characters, so a name can never escape its folder. */
    static String safeFilename(String raw) {
        String n = raw == null ? "" : raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isBlank()) n = "attachment";
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    static boolean accepted(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return ACCEPTED_EXT.stream().anyMatch(n::endsWith);
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        try (in) {
            byte[] b = in.readNBytes((int) MAX_ATTACHMENT_BYTES + 1);
            return b.length > MAX_ATTACHMENT_BYTES ? null : b;
        }
    }

    static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"");
        return t.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").trim();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(s).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
