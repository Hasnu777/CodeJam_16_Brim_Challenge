# Brim AP Automation (CodeJam hackathon, submission Sun ~12:15 pm ET)
- Spec: docs/PRD.md (read it before any work). Decisions log: docs/decisions.md.
- Dataset: dataset_given/ (data/*.csv, inbox/emails.json, incoming_invoices/).
- Stack: Spring Boot 3 + Gradle + Java 21, PostgreSQL (Docker Compose), React + TS + Vite.
- The LLM only reads documents. Everything after extraction is deterministic, tested Java.
- Money = BigDecimal / NUMERIC(14,2). All invoice state changes go through InvoiceWorkflowService,
  which also writes the audit log. Roles enforced server-side on every endpoint.
- Commit after each phase. Never commit .env. Log every non-obvious design choice in docs/decisions.md.
- Priority: all 9 required features working end to end before any bonus.
