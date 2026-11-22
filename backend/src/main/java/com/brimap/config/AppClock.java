package com.brimap.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Single source of "today" so date rules are testable and demos are reproducible (app.business-date). */
@Component
public class AppClock {
    public static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private final LocalDate fixed;

    public AppClock(AppProperties props) {
        this.fixed = props.businessDate() == null || props.businessDate().isBlank() ? null : LocalDate.parse(props.businessDate());
    }

    public LocalDate today() { return fixed != null ? fixed : LocalDate.now(TORONTO); }

    public Instant now() { return Instant.now(); }
}
