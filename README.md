# Brim AP Automation — Harborline Logistics

Harborline pays ~50 supplier invoices a month out of an email inbox. This app takes each one from the AP inbox to a
simulated EFT payment: it reads the document, codes it from history, checks it against 32 rules, routes it to the
right approvers from an editable policy table, pays it under segregation-of-duties rules, and writes every step to
an append-only, hash-chained audit log.

**The design principle:** the LLM only *reads* documents. Everything after extraction (validation, duplicates,
routing, permissions, payments) is deterministic, unit-tested Java. LLM output is untrusted input: it is
schema-validated (structured outputs), normalized and arithmetic-checked before anyone sees it.

## Run it

```bash
cp .env.example .env          # add LLM_API_KEY (optional: without it, PDFs are read by pdftotext + rules, scans by Tesseract)
docker compose up --build
# app: http://localhost:5173    API: http://localhost:8088/api    Swagger: http://localhost:8088/swagger-ui.html
```

The backend loads every file in `dataset_given/` on first start. If you ran an older build before, start clean once
with `docker compose down -v` (the backend now runs as a non-root user and needs a fresh storage volume). Then:

```bash
scripts/reset-and-seed.sh                      # or .\scripts\reset-and-seed.ps1 — wipe + reload in ~6 s, prints row counts vs the dataset README
python scripts/verify_mess_table.py --process  # reset, process the inbox, check every "mess" case (24 checks)
python scripts/eval_extraction.py              # extraction field accuracy on all 56 incoming files
python scripts/flag_report.py                  # every open flag on every bill
cd backend && ./gradlew test                   # 93 unit tests: every rule, routing, release rules, security, normalizer, audit chain
```

### Logins (demo switcher, no password; the server still issues a real session)

| Who | Role | Try |
| --- | --- | --- |
| Jordan Okafor (U004) | AP Clerk | Inbox → Process inbox; review flags, fix fields, accept coding, submit; prepare payments |
| Elise Tremblay (U003) | Finance Manager, delegate for Liam (on leave) | My approvals (approves "on behalf of" Liam); verify Cascadia's bank change; approve vendors |
| Marcus Webb (U002) | Controller / Admin | Policies (edit + preview routing); payments over $100k; audit |
| Priya Raman (U001) | CFO (Admin) | Releases payments the others approved |
| Dev Malhotra (U007) | Approver (Operations) | Department queue and spend |
| Hannah Lindqvist (U006) | Auditor | Read-only everything; audit log, chain verify, CSV export (every write is 403) |
| Kavya Morrison (U037) | Employee | Upload an invoice; sees only their own |

## How it works

```
emails.json ──► classify (keywords, LLM tiebreak) ──► Needs attention (E01–E04, with reason)
     │ attachment
     ▼
document (SHA-256) ──► read: pdftotext -raw + file → Claude (structured JSON) │ fallback: regex / Tesseract │ manual draft
     ▼
normalize (1 234,56 $ → 1234.56, TPS/TVQ → GST5/QST9975, dates) → vendor match (GST → domain → name) → FX → PO/dept
     ▼
GL/department coding from history ("7 of 8 past lines from X went to 5010")
     ▼
32 validation rules → flags with severity, plain-English message, evidence, matched records
     ▼
AP clerk review (fix fields, override codes, acknowledge warnings) → submit
     ▼
routing from the policy table (tiers, new vendor, Legal, over-PO, split amount) + delegation, self-approval,
terminated users, distinct people, limits → snapshotted approval chain
     ▼
approve / reject (assignee or delegate only; never the requester) → APPROVED
     ▼
prepare payment (terms, 2/10 net 30, credit notes netted, pinned bank-account version)
     ▼
release (FM ≤ $100k / Controller; never an approver; vendor approved; bank verified) → EFT batch → settled → PAID
```

Every state change goes through one `InvoiceWorkflowService`, which checks permission, checks the transition and
writes the audit row in the same transaction. Roles are enforced three times on the server: the filter chain
(session required, Auditor writes rejected), `@PreAuthorize` on every endpoint, and scope/segregation checks in
services. Every denial returns 403 with a reason and is itself audited.

## What we detect

| Code | Rule | Severity | Example from the batch |
| --- | --- | --- | --- |
| B01/B02 | Remit-to account not verified / unverified recent bank change | BLOCK (payment hold) | Cascadia 1624, 1625: switched to …9679 on Oct 27 by email, never verified, both invoices pay to it |
| V04 | Lookalike vendor | BLOCK | "Harlow and Stern LLP" (V052) vs "Harlow & Stern LLP" (V026): different GST, bank, domain |
| D01 | Duplicate invoice number | BLOCK | Polar 000961, Harlow & Stern 001468, Cloudbarn INV-01561, Brightline "583" = paid "FA-000583" |
| D02/D03/D04 | Near duplicate / same document / already paid by Brim card | WARN/BLOCK | Voyageur "000903-A" vs paid "000903", 2 days apart |
| V01/V02 | Unknown vendor / vendor not approved | BLOCK (payment hold) | Blue Heron 4443, Zephyr 7590; Riverbend (pending review) |
| T01 | Tax vs ship-to province | WARN | Beaumont FA-000557: QC delivery, GST only (QST $769.66 missing); Northbound: ON HST on an AB delivery |
| S01 | Split to stay under a limit | WARN | Orion 279 + 281 + 287, all Oct 28, $14,543.10 → routed at the combined amount (adds Finance Manager) |
| K01–K05 | Contract: expired, fixed fee, hourly cap, fuel surcharge cap, monthly cap | WARN | Torque C025 expired; Polar 000964 fuel surcharge 22% vs 12% cap; Lattice $8,200 vs $1,320 fee |
| X01–X05 | Line maths, totals, missing fields, dates, credit notes | WARN/INFO | Evergreen 000850 total $100 too high; Summit 000809 dated 25 days in the future; Fixwell CN-FA-000413 = paid FA-000410 |
| P13/PO1/P12 | PO required / PO problem / over PO balance | BLOCK/WARN | Keystone, Hardhat, Brightline… require a PO |
| FX1/FX2 | Foreign currency / rate fallback | INFO | Sparkgrid USD 1,950.00 = CAD 2,626.85 at the Oct 26 rate 1.3471 |
| E01–E04 | Email intake | — | "Invoice attached" with nothing attached; payment inquiry linked to AP-00049 (paid Mar 9) |

Full catalog in `backend/src/main/java/com/brimap/seed/FlagDictionarySeed.java`; one class per rule in
`backend/src/main/java/com/brimap/validation/rules/`, each unit-tested on its dataset example.

## Verification

CI (`.github/workflows/ci.yml`) runs the unit tests, frontend gates, script lint and a secret scan on every PR. The full
pre-review verification (what was run, results, findings fixed) is in [docs/verification-report.md](docs/verification-report.md).

## Bonuses

- **Fraud alerts** (`/fraud`): every payment-fraud signal in one place, including a new lookalike-sender-domain rule (V06). Today: $19,197.22 of payments held, led by Cascadia's unverified bank change.
- **Bank reconciliation** (`/reconciliation`): 332 of 354 statement lines matched to payments; leftovers explained (bank fees, one unexplained PREAUTH DEBIT of $174.90 to investigate); "settle from statement".
- **Approval recommendation** on the invoice screen: "Approve: within contract C012, vendor paid 10 times before, no open flags" / "Look closer: …" / "Do not approve yet: …".
- **Spend analytics** (`/analytics`) by vendor, department or GL with a monthly trend; approvers see their own department only.

## Design decisions

76 logged decisions with the reasoning and the alternatives in [docs/decisions.md](docs/decisions.md), including the
false-positive tuning done on this dataset (D-041, D-042, D-044, D-045). Highlights:

- **Versioned bank accounts** — never edited in place; a change is a request, verified by a second person by callback; payments pin a version.
- **Snapshotted approval chains** — a policy edit never rewrites history.
- **Append-only, hash-chained audit** — a DB trigger rejects UPDATE/DELETE; `GET /api/audit/verify` recomputes the chain and names any edited row.
- **Money** — `BigDecimal` / `NUMERIC(14,2)`, strings in JSON, original currency + CAD + FX rate stored together.

## Scaling to 100× (~5,000 invoices/month)

- Synchronous LLM calls → a job queue with retries and per-file idempotency (the SHA-256 cache already makes re-reads free).
- Duplicate search full-scans → index on (vendor, normalized #) plus an amount-bucket blocking key.
- Context building loads all vendors/invoices per bill → cache reference data, query only the vendor's window.
- Audit table growth → partition by month; keep verifying the chain per partition with an anchored head hash.
- LLM cost/rate limits → batch API for the inbox, caching by file hash, a smaller model for classification.

## Team

Built for the Brim sponsor challenge, McGill CodeJam 2026.
