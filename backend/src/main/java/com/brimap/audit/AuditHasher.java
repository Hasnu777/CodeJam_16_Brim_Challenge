package com.brimap.audit;

import com.brimap.model.AuditEvent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * hash = HMAC-SHA-256(key, prevHash | id | ts | actor | onBehalfOf | action | entity | entityId | before | after | detail)
 * when AUDIT_HMAC_KEY is set, otherwise plain SHA-256 of the same text.
 * With a key that the database admin does not hold, an edited row cannot be re-signed, so tampering is always detected.
 */
public final class AuditHasher {
    private AuditHasher() {}

    public static final String GENESIS = "0".repeat(64);

    /** Read once when the app starts. Missing or blank means plain SHA-256 (local dev and unit tests). */
    private static final byte[] KEY = keyFromEnv();

    public static String hash(AuditEvent e) {
        String canonical = String.join("|",
                n(e.getPrevHash()), String.valueOf(e.getId()), String.valueOf(e.getTs()), n(e.getActor()), n(e.getOnBehalfOf()),
                n(e.getAction()), n(e.getEntity()), n(e.getEntityId()), n(e.getBeforeJson()), n(e.getAfterJson()), n(e.getDetail()));
        byte[] data = canonical.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest;
            if (KEY != null) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
                digest = mac.doFinal(data);
            } else {
                digest = MessageDigest.getInstance("SHA-256").digest(data);
            }
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] keyFromEnv() {
        String k = System.getenv("AUDIT_HMAC_KEY");
        return k == null || k.isBlank() ? null : k.getBytes(StandardCharsets.UTF_8);
    }

    private static String n(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\|"); }
}
