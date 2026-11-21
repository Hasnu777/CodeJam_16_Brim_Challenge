# Brim AP Automation — PRD & Feasibility

Nov 20, 2026 · @Hassan Shah

## Verdict: go, if you scope it as a rules engine with an LLM front end

This is feasible for 4 people in 38 hours, and it suits you better than most teams. It is the ECSE 321 kind of problem (roles, a domain model, state transitions, business rules), and 65% of the score (required features + technical depth) goes to correct logic you can explain, not polish.

**Why you have a real shot:**

- The brief is intimidating. Many teams will skip it or build an "upload PDF, LLM extracts fields" demo and stop. That covers about 2 of the 9 required features.
- Judges test on a **hidden batch of invoices**. A pipeline that is deterministic after extraction (validation rules, duplicate scoring, approval routing as data) holds up. One that asks an LLM "is this a duplicate?" does not.
- The live Q&A is 25%. A clean data model and server-side role enforcement are things you already know how to defend.

**Rough odds.** Your numbers are guesses and so are mine: \~200 people is maybe 50–60 teams, and perhaps 10–25 attempt this. I'd expect only a handful to get all 9 features working end to end. Do that, with flags that explain themselves, and you are plausibly top 3. I can't honestly promise more than "plausibly".

**The one condition:** every required feature works end to end at moderate depth before anyone touches a bonus. Bonuses only count if the required features work, so nine at 70% beats five at 100%.

**Check before you commit (next 20 minutes):**

- Get the dataset and check its format (CSV, JSON, SQL dump). That decides how fast seeding goes.
- Secure an LLM API key that accepts PDFs/images and returns JSON. Ask the sponsor or organizers whether they give credits. \~55 invoices plus a hidden batch is a small volume.
- Ask whether judges test on your laptop or need a hosted URL, and whether they upload the hidden batch themselves or hand it to you.
- Check if you can also submit to the general track with the same project.

## How the app works (plain version)

A web app for Harborline's finance team. Invoices arrive by email, the app reads them, checks them for problems, sends them to the right managers to approve, then pays them (simulated). Every action is logged.

**Three parts:**

- **Frontend (React):** what people click. You log in as one of the 40 fake employees (a dropdown lets judges switch user), and the screens change with that person's role.
- **Backend (Spring Boot):** the brain. It holds every rule: who can do what, what counts as a duplicate, who has to approve, who can pay. The frontend only displays what the backend allows.
- **Database (PostgreSQL):** everything stored. Vendors, users, POs, contracts, 10 months of history (loaded from the CSVs at startup), plus every new invoice, flag, approval, payment and audit entry.

One outside service: an **AI model**, used only to read invoice documents into structured fields. Nothing else depends on AI.

### One invoice's journey

1. **Arrives.** "Process inbox" reads `emails.json`. No attachment, a newsletter or a payment question goes to *Needs attention* with a reason. An invoice attachment becomes a draft bill.
2. **Gets read.** PDF text (or the scan image) goes to the AI, which returns JSON: vendor, invoice #, dates, lines, taxes, total, bank details.
3. **Gets double-checked.** Java code checks that lines add up and taxes add up, and matches the vendor by GST number, then email domain, then name.
4. **Gets coded.** Each line gets a suggested GL account and department, based on how this vendor was coded in the past.
5. **Gets validated.** About 25 rules run (vendor, bank, duplicates, tax, contract, PO). Each problem becomes a flag with a plain-English reason. Some flags block payment.
6. **AP Clerk reviews.** Fixes fields, accepts or changes codes, resolves or acknowledges flags, then submits.
7. **Gets routed.** The policy table builds the approval chain from amount, department, vendor status and category. Delegation and self-approval escalation are applied here.
8. **Gets approved.** Each person in the chain approves or rejects. The server checks they're allowed to.
9. **Gets paid.** Scheduled from the vendor's terms. A Finance Manager or Controller releases it, within their limit and only if they didn't approve it. Status moves Scheduled → Released → Sent → Settled.
10. **Logged throughout.** Each step writes an audit row (who, when, on whose behalf, what changed), which the Auditor can browse and export.

## Dataset findings (first pass)

The dataset makes this easier than the brief suggests. All data is clean CSV plus one JSON inbox, so seeding takes about an hour. All 54 incoming PDFs have a real text layer (`pdftotext -layout` reads them perfectly). Only 2 files are image scans, and those are clean.

**What's in the box:** 16 CSVs (40 users, 8 departments, 56 vendors, 30 GL accounts, 6 tax codes, 27 contracts, 52 POs, 426 historical invoices / 809 lines, 474 approvals, 415 payments, 354 bank-statement rows, 590 card transactions, 306 FX rates, 9 bank changes, 13 policies), `inbox/emails.json` (59 emails), and 56 invoice files. Data as of 2026-11-02.

**Approval policies are plain-English sentences** (e.g. "Invoice total over $10,000 CAD → dept\_manager > finance\_manager"). You hand-translate the 13 rules into structured rows once. That is your editable policy table.

**Mess found in the incoming batch so far** (each is a demo moment and a test case):

| Item | What's wrong | Should trigger |
| --- | --- | --- |
| Cascadia Cartage scan\_8049.png (inv 1624) + PDF inv 1625 | Bank account changed 2026-10-27 **by email request, not callback-verified**. The vendor master already holds the new account (…9679), so a naive "invoice matches master" check passes. Only the change history reveals it. | Payment hold + bank-change fraud alert. This is the star demo case. |
| Invoice HA26-2766 from "Harlow **and** Stern LLP" (V052) | Lookalike of the real vendor "Harlow **&** Stern LLP" (V026): different GST #, different bank account, domain `harlowandstern-llp.example` vs `harlow.example`, email in French | Lookalike vendor / sender alert, Legal extra approval step |
| Polar 000961, Harlow & Stern 001468, Cloudbarn INV-01561 | Invoice # already exists in history | Duplicate (exact) |
| Blue Heron Couriers 4443, Zephyr Data Services 7590 | Not in vendor master | Unknown vendor → hold + onboarding |
| Riverbend Catering 1739 (V054) | Vendor `pending_review`, no GST number on file | New-vendor approval step (P05) |
| Beaumont Riviere FA-000557 | Quebec legal services charged GST only, no QST | Tax mismatch vs ship-to province |
| Orion Strategy 279, 281, 287 | Three consulting invoices all dated Oct 28 ($4,859.00 + $4,881.60 + $4,802.50 = $14,543.10), each under the $10,000 approver limit | Split-invoice flag; route at combined amount (adds Finance Manager) |
| Fixwell CN-FA-000413, Great Lakes CN-GR26-2274 | Credit notes (negative totals) | Credit handling, net against vendor balance |
| Sparkgrid, Tessera | USD, US vendors, no Canadian tax | FX conversion + FOREIGN tax code |
| 2 French invoices (Laurentian, Place Mont-Royal) | `1 234,56 $` number format, TPS/TVQ labels | Normalization |
| 4 emails with no attachment | One "Invoice attached" with nothing attached, one payment inquiry (Torque inv 1743), one supplier statement, one newsletter | Route to Needs attention / ignore |
| Users U012 (on leave, delegate U003), U015 (terminated) | Delegation and inactive-user cases | Delegation recorded, no routing to U015 |

This came from a 20-minute script, so expect more (contract rate caps, fuel surcharge caps, PO balances, card double-payments). Person C should turn this table into the rules test suite.

## Product requirements

**Product:** a web app for Harborline Logistics (fictional, 40 people, 4 offices, \~50 suppliers, \~50 invoices/month) that takes a supplier invoice from the AP inbox to a simulated EFT payment. The right people approve each step and a full audit trail is kept.

**Goal for judging:** a judge picks any role, drops in an invoice they have never seen, and watches it get read, coded, flagged with a plain-English reason, routed, approved and paid, with every step in the audit log.

### Users and permissions (enforced on the server)

| Role | Primary screen | Hard server-side denials |
| --- | --- | --- |
| Employee (requester) | My submissions + submit form | Approve, pay, read other departments' invoices |
| AP Clerk | Review queue: extracted fields, flags, coding | Approve, release payments |
| Approver (dept manager) | My approvals + department spend | Release payments, edit bank details, approve above own limit |
| Finance Manager | Escalations, vendor changes, payment release | Release > $100,000 CAD; release anything they approved |
| Controller / Admin | Users, roles, policy editor, all payments, audit | Approve *and* release the same invoice |
| Auditor | Read-only everything + export | Any write (every POST/PUT/DELETE returns 403) |

Rules for every role: (1) nobody approves an invoice they requested, it escalates to their manager; (2) whoever approved an invoice cannot release its payment; (3) delegate actions are recorded as "X on behalf of Y".

### Required features and acceptance criteria

| # | Feature | Done means (demo-able) |
| --- | --- | --- |
| 1 | Invoice intake | Upload a PDF/image, or click "Process inbox": each email is classified as invoice / no attachment / not an invoice. Invoices become draft bills, the rest go to a "Needs attention" list with the reason. |
| 2 | Extraction | Vendor, invoice #, invoice and due dates, currency, line items, subtotal and each tax amount mapped to a dataset tax code. Works on English and French and on scans. Low-confidence fields are highlighted. Garbage input gives a manual-entry draft, never a crash. |
| 3 | Smart coding | Each line gets a suggested GL account + department with a reason ("7 of 8 past invoices from this vendor went to 5100 Freight"). The clerk accepts or changes it, and the change is logged. |
| 4 | Validation & matching | Checks vendor master (exists, active, tax numbers), contract (rate cap, spend limit, end date), PO (exists, open, same vendor, remaining amount), tax vs province of delivery, and arithmetic. Each failure is a flag with severity and evidence. |
| 5 | Duplicates | Exact, near-exact (normalized invoice #, amount/date tolerance), reformatted, and already paid by Brim card. Shows the matched record and score. |
| 6 | Approval workflow | Policy rules stored as data and editable by Admin. Routes by amount (CAD equivalent), department, vendor status, category and flags. Handles delegation, absence, self-approval escalation. One invoice screen shows document, fields, coding, flags, approval chain, history. |
| 7 | Payments | Approved bills get a scheduled date from terms. Release checks role limit and segregation of duties. Simulated EFT batch. Statuses: Scheduled, Released, Sent, Settled, Failed. Payments dashboard. |
| 8 | Audit trail | Append-only log of every action (actor, on-behalf-of, timestamp, before/after). Payment view links to invoice, approvals and GL codes. CSV export for Auditor. |
| 9 | Vendor management | Vendor list and detail, onboard new vendor (pending until Finance Manager approves). Bills from unapproved vendors are held. Bank-detail change is a request, verified by a second person, and holds payments until verified. |

### Bonus picks (in priority order, only after all 9 work)

1. **Fraud alerts:** bank change shortly before a payment, invoice bank details that differ from the vendor master, lookalike sender domains. Mostly reuses feature 4/9 logic, and it is the story AP teams care about most.
2. **Bank statement reconciliation:** match settled payments to statement debits and list unmatched items with a likely explanation. Deterministic and easy to defend.
3. **Approval recommendation:** "Approve: matches PO and contract, vendor paid 14 times" or "Look closer: 18% above contract rate". Built from flags you already compute.
4. **Spend analytics** by vendor, department, GL (only if time is left).

### Non-goals

Real payments or bank APIs. Real email (read the provided inbox files). Production auth (a seeded login with a role switcher that issues a real server session is enough). Mobile. Multi-company tenancy.

## Architecture and data model

**Stack: keep the ECSE 321 stack.** Spring Boot + PostgreSQL in Docker Compose, React + TypeScript + Vite, OpenAPI-generated types. Familiarity beats a "faster" stack you'd learn at 3 a.m. Spring Security's `@PreAuthorize` and a filter chain also give you a clean answer to "how are roles enforced on the server". Drop the course ceremony: no Umple, no coverage target. Write tests only for the rules engine, duplicates and approval routing, where a bug costs points and tests make good Q&A evidence.

**The design principle to say out loud in Q&A:** the LLM only *reads* documents. Everything after extraction (validation, duplicates, routing, permissions, payments) is deterministic Java code you can unit-test and explain. LLM output is untrusted input: it gets schema-validated, arithmetic-checked and confidence-scored before anyone sees it.

### Invoice lifecycle (state machine)

`RECEIVED → EXTRACTED → IN_REVIEW (clerk) → PENDING_APPROVAL → APPROVED → SCHEDULED → RELEASED → PAID`, with side states `ON_HOLD` (blocking flag, unapproved vendor, unverified bank change), `REJECTED`, `DUPLICATE`. Transitions happen only through one `InvoiceWorkflowService`, which checks permission, writes the audit event, and changes state in a single transaction.

### Core entities

| Area | Tables | Modelling decision to defend |
| --- | --- | --- |
| People | `users`, `departments`, `delegations(from, to, start, end)` | Delegation is time-bounded data, not a flag. Inactive users stay for history. |
| Vendors | `vendors`, `vendor_bank_accounts` (versioned), `bank_change_requests` | Bank details are never updated in place. A new version is verified by someone other than the requester. Payments point to a specific version. |
| Reference | `gl_accounts`, `tax_codes`, `contracts`, `purchase_orders`, `po_lines`, `fx_rates` | Tax rates come from the dataset table, not hardcoded constants. |
| Documents | `emails`, `documents` (file + hash), `extractions` (raw LLM JSON + model + confidence) | Keep the raw extraction so you can show what the model said vs what the clerk corrected. |
| Bills | `invoices` (original currency + CAD equivalent + FX rate used), `invoice_lines`, `invoice_taxes` | Money as `NUMERIC(14,2)` / `BigDecimal`, never floats. |
| Checks | `flags(invoice, rule_code, severity, message, evidence JSON, resolved_by, resolution)` | One row per finding, so the UI explains itself and resolutions are auditable. |
| Workflow | `approval_policies` (ordered rules: conditions JSON → steps), `approval_steps(invoice, seq, required_role/user, acted_by, on_behalf_of, decision)` | Policies are data, so Admin can edit them. Steps are snapshotted per invoice, so a later policy change doesn't rewrite history. |
| Money out | `payments`, `payment_batches`, `bank_statement_lines`, `card_transactions` | A payment links to invoice + bank account version + releaser. |
| Audit | `audit_events(id, ts, actor, on_behalf_of, action, entity, entity_id, before, after, prev_hash, hash)` | Append-only (no UPDATE/DELETE grants). Hash chain makes tampering detectable, which makes a strong Q&A answer. |

### Key algorithms

- **Extraction:** PDFs go through \`pdftotext -layout\` and the text goes to an LLM; images go to a vision-capable LLM. Use a strict JSON schema in both cases (vendor name, GST/QST numbers, invoice #, dates, currency, lines, tax lines, totals, ship-to province, bank details if printed). Then normalize (French number formats like `1 234,56`, dates to ISO). Re-check that lines sum to subtotal and subtotal + taxes = total. Match vendor by tax number, then email domain, then fuzzy name. Any failure becomes a flag, not a silent fix.
- **Coding suggestion:** for each new line, look at historical lines from the same vendor and rank GL/department by frequency. Fall back to token overlap on descriptions, then to the vendor's default account. Show the counts as the reason. Simple, explainable, and it learns from history as required.
- **Duplicates:** a score from (same vendor or same bank account) + normalized invoice # (uppercase, strip non-alphanumerics and leading zeros) + amount within 1% + date within 30 days + file hash. Check against invoices *and* card transactions (fuzzy merchant to vendor, amount, date window). Above a threshold, flag with the matched record linked.
- **Split-invoice detection:** same vendor + department within \~14 days, each below an approval threshold, sum above it.
- **Tax check:** expected tax from ship-to province using the dataset's tax codes (e.g. HST in Ontario, GST + QST in Quebec, GST + PST in BC). Compare to charged, with a rounding tolerance. Foreign and USD vendors handled through the dataset's foreign/exempt codes.
- **Routing:** evaluate policies in order against the invoice's CAD amount, department, vendor status, category and open flags to produce steps. Then resolve each step to a person, apply delegation if they're on leave, and escalate to the manager if they're the requester.

## Feasibility and risks

Seven of the nine features are CRUD plus rules, which is your comfort zone. The real risk is in extraction accuracy on unseen invoices and in integrating everything by Sunday morning.

| Feature | Effort | Risk | Why |
| --- | --- | --- | --- |
| 1 Intake | M | Low | Parsing the provided email files + LLM or keyword classification |
| 2 Extraction | M | **High** | Hidden batch, scans, French. Graded on accuracy. |
| 3 Smart coding | S | Low | Frequency lookup over history |
| 4 Validation | L | Medium | Many small rules; each one is easy, together they take time |
| 5 Duplicates | M | Medium | Tuning thresholds to avoid false positives |
| 6 Approvals | L | Medium | Delegation + escalation + policy editor edge cases |
| 7 Payments | M | Low | State machine + limit checks |
| 8 Audit | S | Low | Cheap if built into the workflow service from hour 1, painful if added later |
| 9 Vendors | M | Low | CRUD + bank-change request flow |

### Top risks and mitigations

1. **Extraction misses on the hidden batch.** Use the best vision model you can get, not a cheap OCR library. Build an eval script on hour \~6 that runs all \~55 incoming invoices and diffs results against whatever ground truth the history gives you. Track field accuracy as a number you can quote to judges. Always fall back to a manual-entry draft.
2. **Late integration.** Agree on the OpenAPI contract and DB schema in hour 1. Run the full pipeline end to end, however ugly, by hour \~14. Then deepen.
3. **Audit added as an afterthought.** Every state change goes through one service that writes the audit row. No exceptions, starting from the first endpoint.
4. **Spring Boot boilerplate speed.** Use Spring Data JPA repos, `ddl-auto` or one Flyway migration, and skip DTO layering where it doesn't matter. Stop caring about the "service calls service" course rule.
5. **Exhaustion.** Sleep in shifts (two people at a time, \~4 hours). Someone who is tired at 3 p.m. Sunday cannot answer Q&A well, and Q&A is 25%.
6. **Demo fragility.** One `reset-and-seed` command that rebuilds the DB from the dataset in seconds. Rehearse the demo on a fresh reset.

### Where you could lose to other teams

A team with someone who has shipped production fintech, or one that starts from a heavy template, could out-polish you on UI (20%). Counter: pick one clear design system (shadcn/ui or Mantine), and build one excellent invoice detail screen that every role reuses. Don't build six mediocre ones.

## Team split, timeline, first steps

### Ownership (4 people, one owner per layer)

| Person | Owns | Features |
| --- | --- | --- |
| A: Platform lead | Schema, seeding, auth + role switcher, `InvoiceWorkflowService`, audit log, approval engine | 6, 8, role enforcement |
| B: Ingestion | Email parsing, LLM extraction, normalization, vendor matching, coding suggestions, eval script | 1, 2, 3 |
| C: Rules | Validation rules, duplicate + split detection, payments, vendor/bank-change flow, reconciliation bonus | 4, 5, 7, 9 |
| D: Frontend | Design system, role-aware layout, invoice detail screen, queues, dashboards, policy editor UI | UI (20%) |

Each person also *explains* one other person's area in Q&A practice, so there is no single point of failure in front of judges.

### Timeline (Fri 10:15 p.m. → Sun \~12:15 p.m.)

1. **Fri 10:15 p.m.–midnight: Lock the foundations.** Open the dataset together. Agree on the schema and the OpenAPI contract. Scaffold repo + Docker Compose. Pick the LLM. Write `reset-and-seed`.
2. **Midnight–Sat 8 a.m.: Walking skeleton.** Seed all data, login as any user, upload one invoice, extract it, see it on a detail page. A and B go to sleep first around 4 a.m., C and D around 8 a.m.
3. **Sat 8 a.m.–noon: One invoice end to end.** Thinnest path through all 9: intake → extract → code → 1–2 rules → route → approve → pay → audit. Ugly is fine.
4. **Sat noon–10 p.m.: Depth.** All validation rules, duplicate scoring, delegation/escalation, policy editor, vendor flows, payments dashboard. Run all \~55 incoming invoices and fix what breaks.
5. **Sat 10 p.m.–Sun 6 a.m.: Bonus + UI polish.** Fraud alerts, then reconciliation. Flag explanations, empty states, role-specific home screens. Sleep in shifts again.
6. **Sun 6–10 a.m.: Feature freeze at 8 a.m.** Bug fixes only. Record a backup demo video. Q&A rehearsal: every person answers the questions in the last section.
7. **Sun 10 a.m.–submission: Buffer.** README with architecture diagram, setup steps, design decisions. Submit at least an hour early.

### First steps, right now

- [ ] Decide go/no-go on this vs your other idea (15 min, max)
- [ ] Download the dataset and list every file and its format
- [ ] Get an LLM API key with credits; test one invoice PDF and one scan in a quick script
- [ ] Ask organizers: hosted URL or local demo? Who runs the hidden batch?
- [ ] Create repo + Docker Compose (Postgres + backend + frontend)
- [ ] Whiteboard the schema from the Core entities table above and agree on it
- [ ] Write the `reset-and-seed` loader for users, vendors, GL, tax codes, POs, contracts, history
- [ ] Stub the role switcher so every teammate can test as any of the 6 roles from the first hour

## Q&A prep: the questions judges told you they'll ask

Everyone on the team should be able to answer each of these in under a minute.

| Question | Answer to have ready |
| --- | --- |
| How are roles enforced? | Spring Security filter + `@PreAuthorize` per endpoint. Segregation-of-duties checks (self-approval, approver ≠ releaser, release limit) live in the workflow service and run inside the transaction. The UI hiding buttons is cosmetic. Demo: call the release endpoint with curl as the approver and get a 403. |
| How are duplicates detected? | Weighted score over vendor/bank account, normalized invoice #, amount tolerance, date window, file hash. Also checked against card transactions. Show the false-positive cases you tuned on. |
| How are mismatches detected? | Each rule is a small class returning a flag with evidence: contract rate cap, PO remaining, tax by province, arithmetic. Unit-tested against known bad invoices from the dataset. |
| How does extraction handle bad input? | Schema-validated JSON, arithmetic cross-checks, per-field confidence, vendor match by tax number. Anything failing becomes a flag or manual-entry draft. Quote your measured field accuracy on the 55 invoices. |
| Why is the data modelled this way? | Versioned bank accounts, snapshotted approval steps, append-only hash-chained audit, original currency + CAD + FX rate stored together, `BigDecimal` money. |
| What breaks at 100× volume (\~5,000 invoices/month)? | Synchronous LLM calls in the request → move to a job queue with retries. Duplicate check full-scanning → index on (vendor, normalized #) and a blocking key on amount bucket. Audit table growth → partition by month. LLM cost and rate limits → batching, caching by file hash. Single DB → read replicas for analytics. |
| What did AI write, and do you understand it? | Be honest about which parts were generated. Each owner walks through their code before the freeze so they can defend every line. |

## Repo structure and conventions

One monorepo with backend and frontend side by side. Each owner mostly works inside their own folders, which keeps merge conflicts rare.

```text
brim-ap/
├── README.md                 # judges read this
├── docker-compose.yml        # postgres + backend + frontend
├── .env.example              # LLM_API_KEY=, DB creds (commit this, never .env)
├── data/                     # Brim dataset: csv/, inbox/, incoming_invoices/
├── backend/                  # Spring Boot, Gradle, Java 21
│   └── src/main/java/com/brimap/
│       ├── config/           # Spring Security, CORS             (A)
│       ├── auth/             # session login, role switcher      (A)
│       ├── model/            # JPA entities                       (A)
│       ├── repository/       # Spring Data repos                  (A)
│       ├── seed/             # CSV loaders, runs on startup       (A)
│       ├── workflow/         # InvoiceWorkflowService, state machine (A)
│       ├── audit/            # append-only, hash-chained log      (A)
│       ├── approval/         # policy engine, routing, delegation (A)
│       ├── intake/           # email parsing, file storage        (B)
│       ├── extraction/       # pdftotext, LLM client, normalizer  (B)
│       ├── coding/           # GL/department suggestions          (B)
│       ├── validation/       # one class per rule -> Flag          (C)
│       ├── duplicates/       # scoring vs history + card txns     (C)
│       ├── payment/          # schedule, release, EFT batch       (C)
│       └── vendor/           # onboarding, bank change requests   (C)
├── frontend/                 # React + TS + Vite                 (D)
│   └── src/
│       ├── api/              # generated from backend OpenAPI
│       ├── auth/             # current user context, role switcher
│       ├── components/       # FlagCard, DocumentViewer, ApprovalTimeline, MoneyCell
│       └── pages/            # one folder per screen (see Screens section)
├── scripts/
│   ├── reset-and-seed.sh     # wipe DB + reload dataset in seconds
│   └── eval_extraction.py    # run every invoice, print field accuracy
└── docs/architecture.md      # diagram + design decisions for judges
```

**Conventions (agree once, then stop discussing):**

- Branches: short-lived, named by area (`backend-approval`, `fe-inbox`). Merge to `main` yourself once it builds. No formal reviews, but say in chat when you merge.
- `main` must always start with `docker compose up`. Anyone who breaks it fixes it before doing anything else.
- Money is `BigDecimal` in Java, `NUMERIC(14,2)` in SQL, and a string in JSON (`"4802.50"`). Format only in the UI.
- Dates are ISO `YYYY-MM-DD`. Timestamps are UTC in the DB and shown in Toronto time.
- IDs: keep the dataset's (`U003`, `V026`, `AP-00001`). New invoices continue the sequence (`AP-00427` onward).
- Every state change goes through `InvoiceWorkflowService`. Controllers never save an invoice directly.
- `.env` is in `.gitignore` before the first commit.

**README skeleton:** pitch → demo video + logins per role → run it (`cp .env.example .env`, `docker compose up`) → how it works (diagram) → what we detect (flag table) → design decisions → scaling to 100× → team.

## API contract

All routes are under `/api`, JSON in and out, with session-cookie auth. Role check = `@PreAuthorize`. "Scope" = an extra row-level check in the service (e.g. an Approver only sees their department). Any call outside role or scope returns **403** with a reason string, and the denial is written to the audit log too.

Roles: **EMP** Employee, **CLK** AP Clerk, **APR** Approver, **FM** Finance Manager, **ADM** Controller/Admin, **AUD** Auditor. "All" = all six.

### Auth and users

| Method | Path | Purpose | Roles (scope) |
| --- | --- | --- | --- |
| GET | `/auth/users` | List of users for the login switcher | public (demo only) |
| POST | `/auth/login` `{userId}` | Start a session as that user | public (demo only) |
| GET | `/auth/me` | Current user, role, limits, active delegations | All |
| GET | `/users` | Users with role, manager, limit, status | ADM, AUD |
| PUT | `/users/{id}` | Change role, limit, status, delegate | ADM |
| POST | `/delegations` `{fromUser, toUser, start, end}` | Set a delegate while away | APR, FM, ADM (own only) |

### Intake and invoices

| Method | Path | Purpose | Roles (scope) |
| --- | --- | --- | --- |
| POST | `/inbox/process` | Read `emails.json`, classify, create drafts | CLK, ADM |
| GET | `/inbox` | Emails with classification and result | CLK, ADM, AUD |
| POST | `/invoices/upload` (multipart) | Upload a file → draft | EMP (own dept), CLK, ADM |
| GET | `/invoices?status=&dept=&flagged=&q=` | List | All (EMP: own; APR: own dept; others: all) |
| GET | `/invoices/{id}` | Full detail: fields, lines, taxes, flags, approval chain, history, document URL | All (same scope) |
| GET | `/invoices/{id}/document` | The original PDF/image | All (same scope) |
| PUT | `/invoices/{id}` | Correct header fields | CLK (status EXTRACTED/IN\_REVIEW only) |
| PUT | `/invoices/{id}/lines/{lineNo}` | Change GL/department/tax code; logs "suggestion overridden" | CLK |
| POST | `/invoices/{id}/reextract` | Re-run extraction | CLK |
| POST | `/invoices/{id}/submit` | Clerk done → run validation + routing → PENDING\_APPROVAL (blocked if unresolved BLOCK flags) | CLK |
| POST | `/flags/{id}/resolve` `{resolution, note}` | Resolve or override a flag (BLOCK flags need FM) | CLK (WARN/INFO), FM, ADM |
| POST | `/invoices/{id}/approve` `{comment}` | Approve the current step | APR, FM, ADM (must be the step's assignee or their active delegate; never the requester) |
| POST | `/invoices/{id}/reject` `{reason}` | Reject → REJECTED | same as approve |
| POST | `/invoices/{id}/hold` / `/release-hold` | Manual hold | CLK, FM, ADM |
| GET | `/approvals/mine` | Steps waiting for me (incl. as delegate) | APR, FM, ADM |

### Payments

| Method | Path | Purpose | Roles (scope) |
| --- | --- | --- | --- |
| GET | `/payments?status=` | Payments dashboard | CLK, FM, ADM, AUD |
| POST | `/payments/prepare` `{invoiceIds}` | Create SCHEDULED payments from APPROVED invoices | CLK |
| POST | `/payments/{id}/release` | Release. Checks: ≤ $100K CAD for FM, any amount for ADM, releaser approved no step of this invoice, vendor bank verified, no open BLOCK flag | FM, ADM |
| POST | `/payments/batch` | Group released payments into a simulated EFT batch file → SENT | FM, ADM |
| POST | `/payments/{id}/settle` | Mark settled (or auto from bank statement match) | FM, ADM |

### Vendors

| Method | Path | Purpose | Roles (scope) |
| --- | --- | --- | --- |
| GET | `/vendors` / `/vendors/{id}` | Vendor master + history, spend, contracts, bank versions | CLK, FM, ADM, AUD; APR read-only |
| POST | `/vendors` | Onboard → `pending_review` | CLK, ADM |
| POST | `/vendors/{id}/approve` | Activate a pending vendor | FM, ADM |
| POST | `/vendors/{id}/bank-change-requests` | Request new bank details (holds payments) | CLK, ADM |
| POST | `/bank-change-requests/{id}/verify` `{callbackDone, note}` | Verify → new version active (must not be the requester) | FM, ADM |

### Admin, audit, reference

| Method | Path | Purpose | Roles (scope) |
| --- | --- | --- | --- |
| GET / PUT | `/policies` | Read / edit approval and payment rules (validated before save) | GET: FM, ADM, AUD; PUT: ADM |
| POST | `/policies/preview` `{invoiceId}` | "Who would approve this under the edited rules?" | ADM |
| GET | `/audit?entity=&actor=&from=&to=` | Audit log | ADM, AUD |
| GET | `/audit/export.csv` | Export | ADM, AUD |
| GET | `/audit/verify` | Re-compute the hash chain, report breaks | ADM, AUD |
| GET | `/ref/gl-accounts`, `/ref/tax-codes`, `/ref/departments` | Dropdowns | All |
| GET | `/analytics/spend?groupBy=vendor\|dept\|gl&from=&to=` | Bonus | FM, ADM, AUD; APR own dept |
| POST | `/admin/reset` | Reset DB to the seeded state | ADM (demo only) |

Generate the TypeScript client from springdoc's `/v3/api-docs`. Then the frontend can start against mocked responses from hour 1.

## Screens per role

The layout is a left sidebar whose items depend on the role, and a top bar with the current user, role badge and a "Switch user" dropdown. Each role lands on a home screen that answers "what do I need to do now?". Build 9 screens, not 30.

| Screen | Who sees it | What it shows | Actions |
| --- | --- | --- | --- |
| **Home / My work** | All (content differs) | Role-specific to-do counts and list: Clerk → to review, flagged, needs attention; Approver → waiting for me, dept spend this month; FM → escalations, vendor/bank approvals, payments to release; Admin → everything + policy shortcuts; Auditor → recent activity; Employee → my submissions by status | Click through |
| **Inbox** | CLK, ADM, AUD | Each email: sender, subject, classification (invoice / no attachment / not an invoice / inquiry), resulting invoice link | Process inbox; open; upload file |
| **Invoice list** | All (scoped) | Table: vendor, invoice #, amount (CAD + original), due date, status chip, flag count by severity, current approver. Filters: status, dept, flagged, vendor | Open; bulk prepare payments (CLK) |
| **Invoice detail** (the main screen) | All (scoped; actions by role) | Left: the original document. Right: header fields (low-confidence ones highlighted), lines with GL/dept suggestion + "why" tooltip, tax breakdown, CAD conversion. Top: flag cards, each with title, explanation, evidence and a link to the matched record. Bottom tabs: approval chain timeline (incl. "on behalf of"), audit history, related (PO, contract, duplicates, payments) | CLK: edit, accept/override codes, resolve flags, submit. APR/FM/ADM: approve/reject with comment. All: nothing they can't do is shown |
| **My approvals** | APR, FM, ADM | Queue of steps assigned to me or my delegator; amount, vendor, top flag, days waiting | Open; approve/reject (from detail) |
| **Payments** | CLK, FM, ADM, AUD | Tabs: Ready to schedule, Scheduled, Released, Sent, Settled, Failed. Totals per tab, due-date sorting, early-discount badges. Disabled release buttons say why ("You approved this invoice", "Over your $100,000 limit") | CLK prepare; FM/ADM release, batch |
| **Vendors** | CLK, FM, ADM, AUD (APR read) | List with status, category, spend YTD, open flags. Detail: master data, bank account versions + change requests, contracts, invoices, card transactions | Onboard; request bank change; FM verify/approve |
| **Policies** | ADM edit; FM, AUD read | The rules table (next section) as editable rows; a "preview routing" box that takes an invoice and shows the resulting chain | Add/edit/reorder rules, save |
| **Audit log** | ADM, AUD | Filterable event stream; click an event to see before/after; chain-integrity badge | Export CSV; verify chain |

**UI rules that win the 20%:**

- Every flag explains itself in one sentence with numbers, e.g. "Bank account changed on Oct 27 by email request and not verified by callback. Remit-to account on this invoice (…9679) is the unverified one."
- Status and severity use the same 3 colours everywhere: red = blocks payment, amber = needs review, grey = info.
- Amounts show CAD, plus the original currency when it differs ("USD 1,950.00 ≈ CAD 2,7xx.xx at 1.3xxx on Oct 28").
- Buttons you can't use are hidden (for employees) or disabled with a reason (finance roles), so judges see the rules at work.
- Empty states say what happens next ("No invoices waiting. Process the inbox to begin.").

## Approval and payment rules (structured)

The dataset's 13 English policies become rows in `approval_policies`. The routing engine reads only this table, so an Admin edit changes behaviour immediately. Amounts use the invoice's **total in CAD** (tax included; USD converted at the invoice-date rate).

### The people the roles resolve to

| Role key | Resolves to | Limit (CAD) |
| --- | --- | --- |
| `dept_manager` | `departments.manager_user_id` of the invoice's department (D01 U007, D02 U008, D03 U009, D04 U002, D05 U010, D06 U011, D07 U012, D08 U001) | 10,000 (approvers) |
| `finance_manager` | U003 Elise Tremblay | 50,000 |
| `controller_admin` | U002 Marcus Webb | 250,000 |
| `cfo` | U001 Priya Raman | 500,000 |

### Approval rules

| Rule | Condition (machine form) | Adds steps | Notes |
| --- | --- | --- | --- |
| P01 | `amountCad <= 10000` | dept\_manager | Base case |
| P02 | `amountCad > 10000` | dept\_manager, finance\_manager | Tiers are cumulative: the highest matching tier wins |
| P03 | `amountCad > 50000` | + controller\_admin |  |
| P04 | `amountCad > 250000` | + cfo |  |
| P05 | `vendor.status = pending_review OR vendor.onboardedAt within 90 days OR vendor unknown` | + finance\_manager | "New vendor" = 90 days (our choice; make it editable) |
| P06 | `vendor.category = Legal` | + finance\_manager |  |
| P12 | `po != null AND amountCad > po.remainingBalanceCad` | + finance\_manager | Plus a WARN flag |

### Rules applied while assigning people (not separate rows)

| Rule | When | What happens |
| --- | --- | --- |
| P07 self-approval | Step's assignee is the invoice requester | Reassign to the assignee's `manager_id` |
| P08 on leave | Assignee `status = on_leave` | Assign to `delegate_user_id`; record `on_behalf_of` |
| Terminated | Assignee `status = terminated` | Skip to their manager; log it |
| Same person twice | One person would fill two steps (e.g. U012 on leave → delegate U003, who is also the finance\_manager step) | Second step escalates to that person's manager (U003 → U002). A chain needs distinct humans. |
| Limit | Assignee's `approval_limit_cad` < amount for the *final* step | Escalate up `manager_id` until the limit covers it |

Steps are deduplicated by role and ordered dept\_manager → finance\_manager → controller\_admin → cfo. The resolved chain is **snapshotted** into `approval_steps` at submit time.

### Holds (block approval/payment until resolved)

| Rule | Condition | Effect |
| --- | --- | --- |
| P13 | `vendor.requiresPo = true AND po = null` | BLOCK flag "PO required" (Warehouse & Packaging, Safety, Repairs & Maintenance, Events & Catering, Office Furniture) |
| — | Vendor unknown, `pending_review` or `on_hold` | Payment held until FM approves the vendor |
| — | Unverified bank change on the vendor | Payment held until FM verifies |

### Payment release rules

| Rule | Condition | Who may release |
| --- | --- | --- |
| P09 | `payment.amountCad <= 100000` | finance\_manager or controller\_admin |
| P10 | `payment.amountCad > 100000` | controller\_admin only |
| P11 | Always | Not anyone who acted on any approval step of the invoice, including as a delegate |
| (brief) | Always | AP Clerk prepares, never releases |

Worked example to demo: a $12,000 Facilities (D07) invoice. dept\_manager U012 is on leave → delegate U003. The finance\_manager step is also U003 → escalates to U002. Release: U003 and U002 both approved, so only U001 (CFO, controller\_admin) can release. Every rule shows up in one invoice.

**Machine form in the DB:** `approval_policies(id, kind, priority, condition_json, adds_roles[], enabled, notes)`, where `condition_json` is a tiny AND/OR tree of `{field, op, value}`. That's about 40 lines of Java to evaluate, and it lets the Admin screen edit rules without code.

## Validation rule catalog

Each rule is one Java class implementing `ValidationRule { List<Flag> check(InvoiceContext ctx); }`. Each produces flags with a code, severity, message and evidence (matched record IDs and numbers). Rules are pure functions of the invoice plus reference data, so each gets a unit test built from a dataset example.

**Severity:** **BLOCK** = cannot be approved or paid until an FM/Admin resolves it. **WARN** = shown to approvers; the clerk must acknowledge before submit. **INFO** = context only.

**Ship-to province** comes from the bill-to address on the invoice, matched to Harborline's 4 offices: Toronto → ON, Vancouver → BC, Calgary → AB, Montreal → QC. Keep the dataset's `ship_to_province` for history.

### Document integrity

| Code | Condition | Sev | Example message |
| --- | --- | --- | --- |
| X01 | Any line `qty × unit price ≠ amount` (> $0.01) | WARN | Line 2: 18 × $428.66 = $7,715.88, invoice shows $7,751.88 |
| X02 | `Σ lines ≠ subtotal` or `subtotal + taxes ≠ total` | WARN | Lines add to $4,362.67; subtotal printed as $4,632.67 |
| X03 | Required field missing or low confidence (vendor, invoice #, date, total) | WARN | Invoice number unreadable, please confirm |
| X04 | Invoice date after today, due date before invoice date, or invoice older than 180 days | WARN | Invoice dated 2027-10-23, likely a typo |
| X05 | Total is negative or document says Credit Note | INFO | Credit note for $1,614.21; will offset next payment to Fixwell |

### Vendor

| Code | Condition | Sev | Example message |
| --- | --- | --- | --- |
| V01 | No vendor match by GST #, sender domain or name | BLOCK | Blue Heron Couriers is not in the vendor master. Onboard before payment. |
| V02 | Vendor `pending_review` or `on_hold` | BLOCK | Riverbend Catering is pending review; Finance Manager approval needed |
| V03 | GST/QST # on invoice ≠ master, or bad format (GST `\d{9}RT\d{4}`, QST `\d{10}TQ\d{4}`) | WARN | GST # on invoice differs from vendor record |
| V04 | Lookalike: name similarity ≥ 0.85 to *another* vendor with a different tax # or bank account | BLOCK | "Harlow and Stern LLP" (V052) closely resembles "Harlow & Stern LLP" (V026) but has a different GST # and bank account |
| V05 | Sender email domain ≠ vendor's `ap_contact_email` domain | WARN | Sent from harlowandstern-llp.example; vendor contact is billing@harlowandstern-llp.example |

### Bank details (payment fraud)

| Code | Condition | Sev | Example message |
| --- | --- | --- | --- |
| B01 | Remit-to account on invoice ≠ vendor's current verified account | BLOCK | Invoice asks for payment to …1234; account on file is …5678 |
| B02 | Vendor bank changed ≤ 30 days ago and `verified_by_callback = no` | BLOCK | Cascadia's bank account was switched to …9679 on Oct 27 by email request and never verified by callback. Both Cascadia invoices pay to it. |
| B03 | Bank change came via `email_request` (even if verified) | WARN | Last bank change requested by email |
| B04 | Another vendor uses the same bank account | WARN | Account …4216 also belongs to V026 |

### Duplicates

Normalize the invoice # (uppercase, strip non-alphanumerics, strip leading zeros, strip prefixes like INV/FA). Compare against all history and the open batch.

| Code | Condition | Sev | Example message |
| --- | --- | --- | --- |
| D01 | Same vendor + same normalized # | BLOCK | Duplicate of AP-00xxx (000961), paid Jul 14 |
| D02 | Same vendor, total within 1%, dates within 30 days, # differs by ≤ 2 characters | WARN | Possible duplicate of FA-000415 (same vendor, $2,866.50, 3 days apart) |
| D03 | Identical file hash, or same vendor + total + date with a different # | BLOCK | Same document as invoice AP-00xxx |
| D04 | Card transaction: merchant fuzzy-matches the vendor, amount within 1%, posted within 45 days | BLOCK | Already paid by Brim card CT-00xxx on Oct 3 ($2,520.00) |

### Tax (CRA)

| Code | Condition | Sev | Example message |
| --- | --- | --- | --- |
| T01 | Charged tax ≠ expected for the ship-to province (tolerance $0.05). Expected from `tax_codes`: ON HST13; QC GST5 + QST9.975 on pre-tax; BC GST5 + PST7 on *goods* lines only; AB and others GST5 | WARN | Delivered to QC: expected GST $385.79 + QST $769.66; invoice charges GST only |
| T02 | Foreign vendor charged Canadian tax, or exempt category (Insurance) charged tax | WARN | Insurance premiums are exempt but HST was charged |
| T03 | GST charged but vendor has no GST # on file or invoice | WARN | GST charged without a registration number, so the input tax credit is at risk |

### Contract and PO

| Code | Condition | Sev | Example message |
| --- | --- | --- | --- |
| K01 | Linked contract expired (`end_date` < invoice date or status expired) | WARN | Torque contract C025 ended Jun 30, 2026 |
| K02 | `fixed_monthly`: subtotal ≠ `monthly_fee` | WARN | Contract fee $2,400; invoice subtotal $2,640 (+10%) |
| K03 | Hourly unit price > `hourly_rate_cap` | WARN | Billed $560/h; contract cap $520/h |
| K04 | Fuel surcharge % > `fuel_surcharge_cap_pct` | WARN | Fuel surcharge 11.6%; cap 10% |
| K05 | Vendor's invoices this month > `not_to_exceed_monthly` | WARN | October total $31,200 exceeds $28,000 cap |
| P13 | Vendor `requires_po` and no PO | BLOCK | Brightline Safety requires a PO; none referenced |
| PO1 | PO not found, closed, or belongs to another vendor | WARN | PO-2026-0012 is closed |
| P12 | Amount > PO remaining balance | WARN | Exceeds PO remaining $1,200 by $340; Finance Manager step added |

### Approval gaming and currency

| Code | Condition | Sev | Example message |
| --- | --- | --- | --- |
| S01 | Same vendor + department, ≥ 2 invoices within 14 days, each ≤ a tier threshold, combined > threshold | WARN | 3 Orion invoices, all dated Oct 28, total $14,543.10; each is under the $10,000 limit. Routed at the combined amount. |
| FX1 | Currency ≠ CAD | INFO | USD 1,950.00 = CAD 2,7xx.xx at the Oct 28 rate 1.3xxx |
| FX2 | No FX rate for the date | INFO | Used previous business day's rate |

### Email intake

| Code | Condition | Result |
| --- | --- | --- |
| E01 | No attachment and body references an invoice | Needs attention: "Says invoice attached, nothing attached. Reply to sender." |
| E02 | Payment inquiry ("where is payment") | Needs attention, linked to the referenced invoice and its payment status |
| E03 | Statement of account | Filed, used for reconciliation; no bill |
| E04 | Marketing / unrelated | Ignored, with reason |

Classification: keyword rules first (cheap, explainable), with the LLM as tiebreaker. Show which one decided.

Keep a `flag_dictionary` table (code, title, severity, explanation template) so the UI and the README pull from one source.

## Demo script (about 4 minutes)

Start from a fresh `reset-and-seed` with the inbox **already processed**, so you don't wait on the AI live. Keep one unprocessed invoice for the live upload. Record a backup video of this exact script on Sunday morning.

| Time | Who's logged in | What you show | What you say |
| --- | --- | --- | --- |
| 0:00 | — | Title + one sentence | "Harborline pays 50 invoices a month from email threads. We take each one from inbox to payment with the right approvals and a full audit trail." |
| 0:15 | Jordan Okafor (AP Clerk) | Inbox: 59 emails → invoices, 4 routed to Needs attention with reasons | "Newsletters, payment inquiries and an 'invoice attached' email with nothing attached are caught, not turned into bills." |
| 0:35 | AP Clerk | Upload a French invoice live → fields appear, `1 234,56 $` normalized, TPS/TVQ mapped to GST5/QST9975, GL suggestion with "why" | "The AI only reads the document. Everything after this is deterministic code." |
| 1:05 | AP Clerk | Invoice list filtered to flagged: duplicates, unknown vendors, tax error, split | "Every flag explains itself." Open Beaumont: QST missing for Quebec delivery. |
| 1:30 | AP Clerk | **Cascadia** (the star): BLOCK, bank changed Oct 27 by email, never verified, both invoices pay to it | "Business email compromise is a common way AP teams lose money. The master data looks fine; the change history doesn't." |
| 1:55 | AP Clerk | **Harlow and Stern** lookalike vendor, side by side with the real one | Different GST #, bank, domain. |
| 2:10 | AP Clerk | Submit the Orion split → chain shows dept manager + Finance Manager because combined $14,543 > $10,000 | "Splitting to stay under a limit doesn't work." |
| 2:30 | Switch → Elise Tremblay (FM) | My approvals: a Facilities invoice she approves **as delegate for Liam (on leave)**; timeline shows "on behalf of" | Delegation is recorded. |
| 2:50 | FM | Payments: try to release that same invoice → button disabled: "You approved this invoice" | Then curl the API as her → 403. "It's enforced on the server, not just hidden." |
| 3:10 | Switch → Marcus Webb (Controller) | Release → EFT batch → Settled; Policies screen: change $10K threshold, preview routing | "Rules are data. Admins change them without code." |
| 3:35 | Switch → Hannah Lindqvist (Auditor) | Audit log for one payment: invoice → coding → approvals → release; chain verified; export CSV | "Audit-ready for the CRA." |
| 3:50 | — | Bonus slide/screen: reconciliation or accuracy number | "We extracted the 56 test invoices with X% field accuracy." |

**Rules:** one driver at the keyboard, one narrator. Everyone else is ready for Q&A on their area. Never type during the demo except the curl and the upload; prepare both in advance.
