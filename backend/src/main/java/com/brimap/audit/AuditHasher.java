package com.brimap.audit;

import com.brimap.model.AuditEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** hash = SHA-256(prevHash | id | ts | actor | onBehalfOf | action | entity | entityId | before | after | detail). */
public final class AuditHasher {
    private AuditHasher() {}

    public static final String GENESIS = "0".repeat(64);

    public static String hash(AuditEvent e) {
        String canonical = String.join("|",
                n(e.getPrevHash()), String.valueOf(e.getId()), String.valueOf(e.getTs()), n(e.getActor()), n(e.getOnBehalfOf()),
                n(e.getAction()), n(e.getEntity()), n(e.getEntityId()), n(e.getBeforeJson()), n(e.getAfterJson()), n(e.getDetail()));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String n(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\|"); }
}
