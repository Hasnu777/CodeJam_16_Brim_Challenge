package com.brimap.auth;

/**
 * A rule-level denial (scope, segregation of duties, limits). Mapped to HTTP 403 with the reason string and
 * written to the audit log as ACCESS_DENIED.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String reason) { super(reason); }
}
