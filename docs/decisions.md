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

## Phase 2: auth, roles, workflow, audit

**D-016 Demo login issues a real server session.** `POST /api/auth/login {userId}` (no password, per the PRD
non-goals) stores a Spring Security context in the HTTP session; the session id is rotated on every switch.
Every permission check reads the role from the session, never from the request body. Terminated users (U015)
cannot log in.

**D-017 CSRF protection off.** The API is JSON-only, same-origin (Vite/nginx proxy) and the session cookie is
`SameSite=Lax`, so a cross-site form post can't carry it. Production would turn Spring's CSRF tokens back on.

**D-018 Three layers of enforcement.** (1) Filter chain: `/api/**` needs a session; any non-GET from an Auditor is
rejected before a controller runs. (2) `@PreAuthorize` on every controller method with the PRD's role table.
(3) Scope and segregation-of-duties checks inside services (`InvoiceAccess`, the workflow service) that throw
`ForbiddenException`. All three return 403 `{status, error, reason}` and write an `ACCESS_DENIED` audit row.

**D-019 Denials are audited in their own transaction** (`REQUIRES_NEW`) so the record survives the rollback of the
denied request. Normal events use `MANDATORY` propagation: they must join the business transaction, so a state
change and its audit row commit or roll back together.

**D-020 Hash chain serialized with a Postgres advisory lock.** `pg_advisory_xact_lock` inside the writer's transaction
makes "read last hash, insert next row" atomic, so concurrent writers can't fork the chain. Ids are assigned
by the chain (1, 2, 3...), so gaps are also detectable. Timestamps are truncated to microseconds before hashing
because that is what Postgres stores. `GET /api/audit/verify` recomputes every hash; tested by editing a row as
DB superuser with the trigger disabled: verify reports `row content does not match its hash` for that row.

**D-021 Reset restarts the audit chain.** `/api/admin/reset` truncates everything including the audit log (demo
only) and then writes `DEMO_RESET` as event 1, so an empty log never looks like a clean one.

**D-022 Approver scope = own department + steps assigned to them or their delegators.** Otherwise Elise (FM, D04)
acting for Liam (D07) could not open the Facilities invoices she has to approve. Employee scope = invoices they
requested.

**D-023 Historical invoices are read-only for review/approval** but may still move along the payment path
(APPROVED/SCHEDULED -> RELEASED -> PAID), since the dataset has 18 scheduled and 2 approved bills waiting to pay.

**D-024 Money serialized as strings** (`"4802.50"`) by a Jackson serializer for `BigDecimal`, per the PRD
convention, so JavaScript never sees a float.
