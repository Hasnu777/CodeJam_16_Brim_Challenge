package com.brimap.common;

/** The request is allowed for this role but breaks a business rule (bad state, open BLOCK flag...). HTTP 409. */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) { super(message); }
}
