package com.brimap.validation;

import com.brimap.model.Severity;

import java.util.Map;

/** A rule finding before it is persisted as a Flag. Evidence = matched record ids and the numbers compared. */
public record FlagDraft(String code, Severity severity, String title, String message, Map<String, Object> evidence) {}
