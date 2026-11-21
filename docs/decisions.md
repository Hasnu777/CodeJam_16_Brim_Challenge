# Design decisions

One entry per non-obvious decision: what, why, alternatives considered.

## Phase 1: scaffold, schema, seeding

**D-001 Spring Boot 3.5.16 rather than what Initializr generates.** Initializr now only offers Boot 4.x;
CLAUDE.md pins Boot 3. We generated the project with Initializr (to get the Gradle 9.9.2 wrapper and layout)
and pinned `org.springframework.boot` 3.5.16, the latest 3.x on Maven Central. Alternative: Boot 4 (newer, but
springdoc/Security APIs differ from what the team knows).

**D-002 Compile with `--release 21`, not a Gradle toolchain.** The dev machine has JDK 25; a toolchain of 21
would force a JDK download. `options.release = 21` guarantees Java 21 bytecode/API on any JDK >= 21, and the
Docker image builds on Temurin 21. Lombok pinned to 1.18.48 (older Lombok breaks on JDK 25).

**D-003 Backend port 8088, not 8080.** Port 8080 is taken by a local Apache httpd on the dev machine. Configurable
via `SERVER_PORT`. The frontend proxies `/api` so the browser never sees the port.

**D-004 Dataset stays in `dataset_given/`.** The PRD's repo sketch says `data/`; the repo already ships the dataset
as `dataset_given/`. The backend reads `APP_DATA_DIR` (default `../dataset_given`, `/data` in Docker, mounted
read-only), so nothing is copied.

**D-005 Schema by Hibernate `ddl-auto=update` + one idempotent SQL file.** Faster to iterate than Flyway during a
hackathon. `db/after-ddl.sql` runs after Hibernate and adds what JPA can't express: the append-only trigger on
`audit_events` and the id sequences. Alternative: Flyway migrations (better for production; noted for scaling).

**D-006 Append-only audit enforced by a database trigger.** `BEFORE UPDATE OR DELETE` on `audit_events` raises an
exception, so even a buggy or malicious code path can't rewrite history. `TRUNCATE` (only used by the demo
reset) does not fire row triggers. Combined with the hash chain (phase 2) tampering is both prevented and detectable.

**D-007 Dataset ids are primary keys.** `U003`, `V026`, `AP-00001` are the PKs. New rows continue the sequences
(`AP-00427`, `PAY-00416`, `V057`, `BC-010`) via Postgres sequences that the reset restarts.

**D-008 Versioned bank accounts built from the vendor master + change history.** For each vendor with a bank
change we create version 1 (SUPERSEDED, only the old last 4 digits are known) and version 2 (ACTIVE, full account
from the vendor master). Version 2 is `verified` only if the change was callback-verified. BC-009 (Cascadia,
2026-10-27, email request, not verified) is seeded as a PENDING change that the dataset already applied to the
master (`appliedBeforeVerification = true`), which is exactly why a naive "invoice matches master" check passes.

**D-009 Delegations are rows, derived from HR status.** U012 (on leave since 2026-10-20, delegate U003) becomes an
open-ended `delegations` row. Routing reads `delegations`, not the user flag, so delegations can be time-bounded
and created by users themselves (`POST /delegations`).

**D-010 Historical status mapping.** Invoices: paid/scheduled/approved/rejected/pending_approval map to the PRD
lifecycle enum. Payments: `settled` -> SETTLED, `outstanding` -> SENT (sent to the bank, not yet on the
statement), `scheduled` -> SCHEDULED.

**D-011 Approval policies hand-translated once.** `PolicySeed` holds the structured form of the 13 English rules
(condition JSON tree + roles). The English sentence is kept in `source_text`. P07/P08 are `assignment` rules
(applied while resolving people), P09-P11 `payment_release`, P12/P13 `match`.

**D-012 Invoice-number normalization keeps the credit-note prefix.** Uppercase, strip non-alphanumerics, strip a
generic prefix (INV, FA, ...) when followed by digits, strip leading zeros. `CN` is *not* stripped, otherwise
credit note CN-FA-000413 would be flagged as a duplicate of invoice FA-000413.

**D-013 Business date defaults to the dataset's as-of date (2026-11-02).** `BUSINESS_DATE` fixes "today" for date
rules (future-dated invoice, bank change within 30 days, payment scheduling) so the demo behaves the same on any
day. Set it empty to use the real date.

**D-014 Mantine as the design system.** The PRD suggests shadcn/ui or Mantine; Mantine gives tables, forms,
badges and app-shell layout out of the box with no Tailwind setup.

**D-015 OpenAPI types generated via `npx`, not a dev dependency.** `openapi-typescript` requires TypeScript 5 while
Vite scaffolds TypeScript 7; `npm run gen:api` runs it in an isolated `npx` sandbox against `/v3/api-docs`.
