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

## Phase 3: intake and extraction

**D-025 `pdftotext -raw -enc UTF-8`, not `-layout`.** `-layout` reproduces columns but scrambles the totals block
(values land on the row below their label, e.g. Cascadia 1625). `-raw` gives reading order with every label next
to its value, and UTF-8 keeps French accents (`Échéance`, `Facture n°`).

**D-026 The LLM sees the text layer AND the original file.** For PDFs we send the PDF as a document block plus the
pdftotext output ("copy numbers exactly from here"); for scans an image block. Layout from the file, exact
characters from the text layer.

**D-027 Structured outputs via the Anthropic Java SDK, amounts as printed.** The schema is derived from the
`ExtractedInvoice` record (`outputConfig(ExtractedInvoice.class)`), so the response must validate. The model
transcribes, it does not compute: amounts/dates come back as printed strings (`"1 234,56 $"`), and deterministic
Java (`InvoiceNormalizer`, unit-tested) turns them into BigDecimal/LocalDate and maps TPS/TVQ/HST/PST labels to
the dataset tax codes. Default model `claude-opus-5-5` (override with `LLM_MODEL`); server-side refusal fallback
enabled. A refusal, schema failure or API error is never fatal: the pipeline falls back (D-029).

**D-028 LLM responses cached by file SHA-256 + model + prompt version** (`storage/llm-cache/`). Re-running the inbox
or the eval costs nothing, demos don't wait on the API, and the cache survives DB resets. "Re-extract" bypasses it.

**D-029 Three-level fallback, never a crash.** (1) LLM; (2) offline label-based regex parser over the text layer
(pdftotext, or Tesseract OCR for images, with OCR-misread tax labels like `OST` -> `QST` repaired); (3) an empty
manual-entry draft with the reason. Every level records its method in `extractions` and the audit detail.

**D-030 No LLM key was present in `.env` during the build** (`LLM_API_KEY` is empty). Everything was built and
measured on the offline path; the LLM path switches on automatically when a key is added. Offline eval on all 56
files: 725/728 fields (99.6%). Caveat for Q&A: the offline parser and the ground-truth script both key on the
labels in these templates, so this number overstates offline accuracy on unseen layouts; the LLM is the path for those.

**D-031 Ground truth for the eval is built independently** (`scripts/build_ground_truth.py`): its own Python
regexes over the PDF text, plus hand transcription of the 2 scans. It surfaced real mess: Evergreen 000850
prints subtotal 3,180.00 + GST 159.00 = total 3,439.00 ($100 over), and Summit 000809 is dated 2026-11-27 (future).

**D-032 Vendor matching order: GST number, then sender domain, then fuzzy name** (Jaro-Winkler >= 0.93 on names
with legal suffixes removed and `&` = `and`). GST first is what makes the Harlow lookalike resolve to V052 (its
own GST number) rather than to the real Harlow & Stern (V026); the lookalike rule then compares the two. When a
domain is shared by several vendor records (V001/V051 Polar Freight), the closest name wins.

**D-033 Department and requester for emailed bills are inferred**: from the referenced PO when there is one,
otherwise the vendor's most frequent (department, requester) in history. Vendors with no history (new/unknown)
are left blank and flagged for the clerk. An employee's own upload is always theirs, in their department.

**D-034 Ship-to province from the bill-to address**: Harborline's four offices (Toronto ON, Vancouver BC,
Calgary AB, Montreal QC), else a province code before a postal code.

**D-035 Email intake: keyword rules first, LLM only as tiebreaker**, and the email records which one decided
(`classifiedBy`). An email with an attachment is an invoice unless it is a statement. Payment inquiries are linked
to the invoice they mention and its payment status (EM-057 -> AP-00049, paid 2026-03-09).

**D-036 Inbox processing is asynchronous** (4 workers) because each attachment can be an LLM call; workers run with
the clicking user's security context so the audit actor is correct. Upload is synchronous (one file).

## Phase 4: coding suggestions and validation rules

**D-037 One class per rule, pure functions of an `InvoiceContext`.** `InvoiceContextFactory` is the only part that
touches the database; it loads the invoice, its lines/taxes, vendors, bank account versions, bank changes,
contracts, PO and amount already billed on it, every other invoice, document hashes, card transactions, tax codes,
today and the approval thresholds. Each of the 32 rule classes is unit-tested with a hand-built context from the
dataset example the PRD cites (66 tests in total).

**D-038 Flags are never deleted.** Re-validation keeps a finding whose code+message is unchanged (with its
acknowledgement or override), adds new findings, and marks OPEN flags whose condition no longer holds as
RESOLVED/AUTO_CLEARED. So a clerk's fix (e.g. entering the department) visibly clears X03 in the history.

**D-039 Who may resolve what.** WARN/INFO: the clerk acknowledges or resolves. BLOCK: only FM or Controller, and only
with a note (403 / 400 otherwise). The flag row stores who, when, resolution and note; an audit event is written.

**D-040 Exact duplicates (D01/D03) park the bill in DUPLICATE** instead of IN_REVIEW. Other BLOCK flags (vendor,
bank) leave the bill reviewable but block submission/approval/payment until resolved, so the clerk can still
correct fields and the FM can act on the vendor.

**D-041 S01 only groups bills still awaiting approval.** The first run grouped each monthly utility bill with last
month's already-paid one (Prairie Natural Gas, Prairie Express). A split is several bills going through approval
together; history that was approved separately is not. The group must also cross a threshold read from the
policy table (so an Admin edit of the $10,000 tier moves the split detector too).

**D-042 D02 near-duplicate window is 10 days and the edit distance must be < half the number's length.** With the
PRD's 30 days every monthly subscription bill (same amount, next sequential number, ~30 days later: Cloudbarn,
Quillpad, Fibrewave, ClearView, Benefitwise) matched last month's. And "differs by <= 2 characters" flagged Orion
279 vs 281. After tuning, D02 still catches Voyageur "000903-A" vs paid "000903" (2 days apart, same amount).

**D-043 Duplicate score is shown on every D-flag**: same vendor 0.30, same normalized # 0.40 (or within 2 chars 0.20),
amount within 1% 0.20, dated within 30 days 0.10, identical file = 1.0. Credit notes never match invoices (sign).

**D-044 D04 ignores AP virtual-card payments of other known invoices** (memo "AP payment AP-xxxxx"), otherwise every
monthly SaaS bill paid by virtual card would look already paid. Merchant match needs prefix containment or
Jaro-Winkler >= 0.90 ("PAPERCLIP STATIONERS" must not match "Paper Trail Office Supply").

**D-045 V04 lookalike needs a different bank account or email domain, and flags only the less-established record.**
Same bank + same domain with a different GST # (V001/V051 Polar, V002/V053 Great Lakes) is a duplicate vendor
record, reported by B04. And the genuine Harlow & Stern (V026, paid history) must not be blocked because a
lookalike exists; only V052 (no history) is.

**D-046 T01 is computed from the tax_codes table by ship-to province, per tax**, with PST on goods lines only (line type
from the vendor's history). Unregistered suppliers charging nothing (Riverbend) are not told to charge GST;
charging without a number is T03. Foreign vendors and Insurance are T02.

**D-047 K04 computes the fuel surcharge percentage** (surcharge / other freight lines) instead of trusting the printed
label. P12 compares pre-tax amounts against the PO's remaining balance.

**D-048 Cross-invoice rules re-run after a batch.** Inbox workers run in parallel, so a bill may be validated before
its duplicate or split partner exists. When the batch finishes, every open bill is validated again; after an
upload, the same vendor's open bills are. (Without it, the Orion split was missed.)

**D-049 Coding suggestions learn from history**: the vendor's past line coding by frequency ("7 of 8 past invoice
lines from X went to 5010"), else token overlap with all past lines ("10 of 10 similar past lines went to 5010,
e.g. ... on AP-00028"), else the vendor's default account. Clerk overrides are kept and logged with the suggestion
they replaced; re-coding never touches an overridden line.

**D-050 Real mess found beyond the PRD's table** (all flagged): Polar 000964 fuel surcharge 22% vs 12% cap (K04);
Northbound charges ON HST on an AB delivery and Ironwood no HST in ON (T01); Signal & Sparrow charges BC PST on
services (T01); Northstar 24 x $150 printed as $3,800 (X01); Lattice $8,200 vs $1,320 fixed fee, Pinecone +6%,
Aurora +9% (K02); Redline over its monthly cap (K05); Lumen invoice 210 days old (X04); Brightline "583" is a
reformatted duplicate of paid "FA-000583" (D01); Fixwell credit note CN-FA-000413 equals paid FA-000410 (X05).
