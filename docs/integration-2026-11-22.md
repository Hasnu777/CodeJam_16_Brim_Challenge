# Integration: every team branch in one build (22 Nov 2026)

Branch: `integration/all`. It is built on `matt-work` (the UI everyone keeps) with every other branch merged in. Nothing
was merged into `main` or into anyone's own branch: each branch is unchanged on GitHub.

## How the branches relate

All of them start from `phase10-docs-handoff`. Several contain each other, so there were only four lines of work:

```
phase10-docs-handoff
├── afra-work ──────────┬── matt-work ............ base of integration/all
│                       └── afra-bonus-features .. merge 3
├── feat/audit-vendor-gaps ── feat/reset-button .. merge 1
└── feat/approvals-payments ── feat/human-readable-ui .. merge 2
```

- `matt-work` already contains all of `afra-work` (merged by Matt; his handoff explains the conflicts).
- `afra-bonus-features` = `afra-work` + the Brim card optimizer (2 commits).
- `feat/reset-button` = `feat/audit-vendor-gaps` + the Home reset button.
- `feat/human-readable-ui` = `feat/approvals-payments` (features 6 and 7) + names instead of ids.

## What each branch brings

| Branch | Owner | What it adds | Its own notes |
|---|---|---|---|
| `afra-work` | Afrasiyab | Live IMAP inbox next to `emails.json`; non-invoices kept out of the review queue; Gmail-style email view; PO suggestions; spend analytics by vendor category | `docs/decisions.md` D-081..D-084 |
| `matt-work` | Matt | The "Lovable" design across the app (theme, shell, Home, invoice page, flag banner and pop-up, monthly spend chart); `.env` variants git-ignored | `docs/handoff-2026-11-22.md`, D-085..D-088 |
| `feat/audit-vendor-gaps` | Abdulrahman | Tamper alert on Fraud alerts; TRUNCATE blocked on the audit table; reset only in demo mode; HMAC-signed audit chain; Admin re-seal; written callback note required to verify a bank change | `docs/audit-and-vendor-controls.md` |
| `feat/reset-button` | Abdulrahman | Admin "Reset demo data" on Home (demo mode only; reloads data and processes the inbox) | same doc, section 6 |
| `afra-bonus-features` | Afrasiyab | Brim card optimizer: per-payment card advice, audited switch to card and back, "left on the table" panel | `docs/brim-card-optimizer.md`, D-089 |
| `feat/approvals-payments` + `feat/human-readable-ui` | Hassan | Features 6 and 7: reopen, re-route, proxy self-approval, locking, CFO exception, delegation fix, stale holds, re-prepare, 24-check live script; names instead of ids in the UI | D-090..D-099 |

## Merges, conflicts and how each was resolved

**Merge 1: `feat/reset-button` (with `feat/audit-vendor-gaps`)**
- `.env.example`, `application.yml`, `docker-compose.yml`: both sides added settings (IMAP from afra-work;
  `APP_DEMO_MODE`, `AUDIT_HMAC_KEY` from the audit branch). Kept both.
- `HomePage.tsx`: kept Matt's header and stat cards; the reset card sits under the header, restyled as an `ap-stat` card.

**Merge 2: `feat/human-readable-ui` (features 6 and 7)**
- `HomePage.tsx`, `InvoiceDetailPage.tsx`, `AuditPage.tsx`, `FlagCard.tsx`: Matt's (and the audit branch's) layout kept;
  the 6/7 behaviour and the name lookups re-applied inside it: department names, "for <person>" delegate badges,
  people and readable actions in activity and the audit log, warnings listed by title, the current approval round and
  the "earlier approval round" section, Reopen and Re-route buttons.
- `AnalyticsPage.tsx`: Afra and Matt's breakdown (with category grouping) kept.
- `docs/decisions.md`: everyone had numbered from D-081. Matt/Afra keep D-081..D-088, the card is D-089, and the 6/7
  decisions became D-090..D-099. Every reference in code, tests, scripts and docs was updated.

**Merge 3: `afra-bonus-features` (Brim card)**
- `PaymentController.java`, `PaymentService.java`: both sides added an endpoint (`/reprepare` and `/method`). Kept both.
- `docs/decisions.md`: the card decision placed as D-089.

## Fixes made after merging

1. **Export CSV button was invisible** (blue text on blue) on the Audit log. Matt's CSS coloured every link inside a
   page, including Mantine buttons rendered as links. The rule now skips buttons (`index.css`).
2. **Reset button label was cut off** ("Reset demo d") on narrower screens. The button no longer shrinks.
3. **Card limit enforced on the switch.** The $50,000 per-charge limit was advice only; an $80k bill could still be
   moved to card. The server now refuses it.
4. **Card payments no longer get the "bank details changed, re-prepare it" blocker.** Bank details don't apply to a
   card charge.
5. The card panel uses Matt's `ap-stat` card style; API types regenerated (`npm run gen:api`) so the card fields are
   typed from the backend instead of by hand.

## Brim virtual card bonus: review

**Verdict: feasible, kept.** It works end to end and is covered by a unit test (`CardPaymentAdvisorTest`) and a new
live check (scenario 13 in `scripts/verify_approvals_payments.py`): clerk switches to card and back (200), Auditor
refused (403), unknown method (400), vendor that doesn't accept card (409), three audit rows, and the payment still
needs a normal release by someone who didn't approve it.

Issues to know about (not fixed, so the team can decide):
- **It is one "Brim AP card", not virtual cards.** The goal was a Brim virtual card for vendors that accept card. A
  virtual card would be a card number issued per vendor (or per payment), locked to that vendor and amount, shown
  masked on the payment, and expired after use. That is a moderate addition on top of what exists: a `virtual_cards`
  table, issue at release, masked number in the payment row and batch file, audited.
- **Card charges go into the "EFT batch file"** with `BRIM-AP-CARD` in the account column. Fine for a simulation; in
  reality a card charge is not an EFT.
- **Bank reconciliation ignores card payments.** They would settle against the card statement (`card_transactions.csv`),
  not the bank statement.
- **The terms are assumptions** (21-day grace, 5%/yr value of cash, 1% rewards, $50k limit) and the page prints them.
  Vendor card surcharges and the overall credit limit are not modelled.

## Verification on `integration/all`

| Check | Result |
|---|---|
| Backend unit tests (`gradlew test`) | 148 tests, 0 failures |
| Frontend `tsc -b` | clean |
| Frontend lint | 5 warnings, all known (4 from D-076, 1 from afra-work's InboxPage) |
| `scripts/verify_approvals_payments.py` (features 6, 7 and the card bonus, live) | 24/24 |
| `scripts/verify_mess_table.py --process` (whole incoming batch, live) | 24/24 |
| Browser, as Marcus (Admin) | Login, Home (reset card), Payments (card panel, release buttons), Audit log (chain verified, Export CSV), invoice page |

## Not verified

- Every action on every page as every role after the restyle (Matt's handoff notes the same).
- The live IMAP inbox (needs real mailbox credentials).
- `AUDIT_HMAC_KEY` set to a real key (left empty, so plain SHA-256 is used; after setting it, reset the data).

## Run it

```powershell
docker compose up --build -d
powershell -ExecutionPolicy Bypass -File .\scripts\reset-and-seed.ps1
```

Then Process inbox as Jordan (or use Marcus's "Reset demo data" button on Home, which does both).
