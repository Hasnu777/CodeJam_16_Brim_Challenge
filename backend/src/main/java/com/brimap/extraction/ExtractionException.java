package com.brimap.extraction;

public class ExtractionException extends RuntimeException {
    public ExtractionException(String message, Throwable cause) { super(message, cause); }

    public ExtractionException(String message) { super(message); }
}
