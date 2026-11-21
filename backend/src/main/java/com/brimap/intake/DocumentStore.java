package com.brimap.intake;

import com.brimap.config.AppProperties;
import com.brimap.model.StoredDocument;
import com.brimap.repository.StoredDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/** Content-addressed file storage: storage/docs/<sha256>.<ext>. The hash also feeds duplicate rule D03. */
@Component
@RequiredArgsConstructor
public class DocumentStore {
    private final AppProperties props;
    private final StoredDocumentRepository docs;

    public StoredDocument store(byte[] bytes, String filename, String emailId, String uploadedBy) {
        String sha = sha256(bytes);
        String ct = contentType(filename, bytes);
        String ext = switch (ct) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> ".bin";
        };
        Path dir = Path.of(props.storageDir(), "docs");
        Path target = dir.resolve(sha + ext);
        try {
            Files.createDirectories(dir);
            if (!Files.exists(target)) Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot store document", e);
        }
        StoredDocument d = new StoredDocument();
        d.setFilename(filename);
        d.setContentType(ct);
        d.setSha256(sha);
        d.setSizeBytes(bytes.length);
        d.setStoragePath(target.toString());
        d.setEmailId(emailId);
        d.setUploadedBy(uploadedBy);
        d.setCreatedAt(Instant.now());
        return docs.save(d);
    }

    public Path path(StoredDocument d) { return Path.of(d.getStoragePath()); }

    /** Sniff by magic bytes first (never trust the extension alone), then by name. */
    public static String contentType(String filename, byte[] b) {
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8) return "image/jpeg";
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
