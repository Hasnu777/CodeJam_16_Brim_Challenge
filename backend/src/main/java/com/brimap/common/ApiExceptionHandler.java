package com.brimap.common;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.auth.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Uniform JSON errors: {status, error, reason}. Every 403 is also written to the audit log. */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class ApiExceptionHandler {
    private final AuditService audit;

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(ForbiddenException e, HttpServletRequest req) {
        audit.recordDenial(Auth.actorId(), req.getMethod(), req.getRequestURI(), e.getMessage());
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /** @PreAuthorize failures. */
    @ExceptionHandler({AuthorizationDeniedException.class, AccessDeniedException.class})
    ResponseEntity<Map<String, Object>> denied(RuntimeException e, HttpServletRequest req) {
        String reason = Auth.current()
                .map(u -> "Role " + u.role() + " is not allowed to " + req.getMethod() + " " + req.getRequestURI())
                .orElse("Not logged in");
        audit.recordDenial(Auth.actorId(), req.getMethod(), req.getRequestURI(), reason);
        return body(HttpStatus.FORBIDDEN, reason);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NotFoundException e) { return body(HttpStatus.NOT_FOUND, e.getMessage()); }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<Map<String, Object>> rule(BusinessRuleException e) { return body(HttpStatus.CONFLICT, e.getMessage()); }

    // Spring's wrapper (repository calls) and the raw JPA/Hibernate exceptions (flush inside a service method): two people
    // acting on the same payment or approval step at once -> the second gets 409, never a 500.
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class,
            org.hibernate.StaleStateException.class})
    ResponseEntity<Map<String, Object>> conflict(Exception e) {
        return body(HttpStatus.CONFLICT, "Someone else changed this record; reload and try again");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).reduce((a, b) -> a + "; " + b).orElse("Invalid request");
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badArg(IllegalArgumentException e) { return body(HttpStatus.BAD_REQUEST, e.getMessage()); }

    static ResponseEntity<Map<String, Object>> body(HttpStatus s, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s.value());
        m.put("error", s.getReasonPhrase());
        m.put("reason", reason);
        return ResponseEntity.status(s).body(m);
    }
}
