# Audit trail and vendor controls: what changed (#8 and #9)

Owner: Abdulrahman. Branches: `feat/audit-vendor-gaps` (everything below) and `feat/reset-button` (the Home-page reset button).
This page explains each feature, how to try it, and what it does not do. The reasoning behind each choice is in
`docs/decisions.md`.

## Before you run it

| Setting | Where | What to do |
|---|---|---|
| `APP_DEMO_MODE` | `docker-compose.yml` (already `true`) | Nothing. Reset works as before. Set it to `false` to see reset refused. |
| `AUDIT_HMAC_KEY` | your own `.env` (never committed) | Optional. Generate one with `openssl rand -hex 32`. **After setting or changing it, run `scripts/reset-and-seed.sh`**, or every existing audit row shows as tampered. Leave it empty and the app uses plain SHA-256 as before. |

## 1. A broken audit chain is raised as a Fraud alert

**Before:** you only found out the audit log had been edited if someone opened Audit log and clicked Verify.
**Now:** every time the Fraud alerts page loads, it re-checks the chain. If any row was edited or removed outside the
app, a red **BLOCK · Audit log tampered** alert appears at the top, naming the rows:

> Tampering detected: 1 audit record(s) (#1) were changed outside the app by someone with direct database access.
> Their contents can't be trusted; check database access logs and restore from backup.

The Verify tooltip on the Audit log page uses the same plain wording ("this record was changed after it was written, by
someone with direct database access").

**Code:** `bonus/FraudService.java`, `audit/AuditService.verify()`.

## 2. The audit log can't be wiped (TRUNCATE guard + demo-only reset)

**Before:** the database blocked UPDATE and DELETE on `audit_events`, but not TRUNCATE (empty the whole table). The demo
reset uses TRUNCATE, so any Admin could wipe the entire history with one call.
**Now:**
- A `BEFORE TRUNCATE` trigger refuses to empty `audit_events`:
  `ERROR: audit_events is append-only (TRUNCATE rejected)`.
- The demo reset gets through by setting `app.allow_reset = on` for its own transaction only.
- `POST /api/admin/reset` only works when `APP_DEMO_MODE=true`. Otherwise it returns 403:
  "Reset is disabled outside demo mode (set APP_DEMO_MODE=true)". The default in `application.yml` is `false`
  (safe); `docker-compose.yml` turns it on for the hackathon.

**Code:** `resources/db/after-ddl.sql`, `seed/DataSeeder.reset()`, `admin/AdminController.reset()`,
`application.yml` (`app.demo-mode`).

## 3. The audit chain is signed with a secret key (HMAC)

**Before:** each row's fingerprint was plain SHA-256, which anyone can compute. Someone with database access could edit
a row and then recompute every fingerprint after it, and the chain would look valid.
**Now:** when `AUDIT_HMAC_KEY` is set, fingerprints are HMAC-SHA-256 with that key. Without the key you cannot produce a
valid fingerprint, so an edit is always detected. In a real company the key is held by someone other than the database
admin, so changing history undetected would take two people colluding.

**Code:** `audit/AuditHasher.java`, `docker-compose.yml`, `.env.example`.

## 4. Admin "Verify chain" re-seals a broken chain (and the evidence stays)

- **Admin** (Marcus, Priya) clicks **Verify chain** on a broken chain: the app accepts the rows' current contents,
  re-signs every row, and the badge turns green. It also writes an **AUDIT_RESEALED** audit entry (who, when, which rows
  were broken).
- **Auditor** (Hannah) clicks it: it only re-checks. It stays red. The independent checker can't make it green.
- Opening the Audit log page never re-seals; only an admin's click does (`POST /api/audit/reseal`).
- Fraud alerts keeps a yellow **WARN · Audit log re-sealed after tampering** for every re-seal:

> Priya Raman re-sealed the audit log on Nov 22 at 1:02 AM. Record #1 had been changed outside the app; the change was
> accepted, not undone.

The database trigger lets this one UPDATE through because the re-seal sets `app.allow_reseal = on` for its own transaction.

**Code:** `audit/AuditService.reseal()`, `audit/AuditController` (`/reseal`, Admin only), `bonus/FraudService.java`,
`repository/AuditEventRepository`, `pages/AuditPage.tsx`, `resources/db/after-ddl.sql`.

## 5. Verifying a bank change needs a written callback note

**Before:** "Callback done, verify" always sent `callbackDone: true`, so the server's callback check could never fail,
and a change could be verified with an empty note.
**Now:** verifying (not rejecting) requires a note, e.g. "Called the number from our onboarding file; Sam confirmed the
new account." Otherwise: "Write down the callback before verifying: which number on file you called and who confirmed
the new account". The note is saved on the change and in the audit log. Rejecting needs no note (saying no is always safe).

**Code:** `vendor/VendorService.verify()`.

## 6. Reset demo data button (branch `feat/reset-button`)

Admins see a **Demo tools** card on Home with **Reset demo data**. It asks for confirmation, calls the reset, processes
the inbox, and refreshes the screen. With demo mode off, the server refuses and the red pop-up shows why.

**Code:** `pages/HomePage.tsx` (`DemoReset`).

## How to try it (about 10 minutes)

```bash
docker compose up --build
scripts/reset-and-seed.sh                 # ALL MATCH; then Process inbox as Jordan

# Tamper with the log like a database admin would
docker compose exec db psql -U brimap brimap -c "ALTER TABLE audit_events DISABLE TRIGGER audit_events_no_update; UPDATE audit_events SET detail = 'hacked' WHERE id = 1; ALTER TABLE audit_events ENABLE TRIGGER audit_events_no_update;"
```

1. Fraud alerts (refresh) → red **Audit log tampered (#1)** at the top.
2. Hannah → Audit log → Verify chain → stays red; hover the badge for the reason.
3. Marcus → Audit log → Verify chain → green, an **AUDIT_RESEALED** row on top; Fraud alerts shows the yellow re-seal warning.
4. `docker compose exec db psql -U brimap brimap -c "TRUNCATE audit_events;"` → refused.
5. `APP_DEMO_MODE=false docker compose up --build`, then `scripts/reset-and-seed.sh` → fails with 403. Restart normally afterwards.
6. Jordan requests a bank change on any vendor; Elise clicks **Callback done, verify** with an empty note → refused; with a note → verified.

## #8 and #9 checklist (all tested)

| Requirement | Status |
|---|---|
| #8 every action logged, exportable to CSV, chain verifiable | Built in Phase 2; tested |
| #8 raise fraud issues | Tamper alert and re-seal warning on Fraud alerts |
| #9 invoices checked against existing vendors | V01 unknown vendor, V04/V06 lookalikes |
| #9 Admin onboards from anywhere; Clerk from an invoice; pending until approved | Tested (Blue Heron → V057) |
| #9 Finance Manager/Admin approves, never the person who onboarded | Tested |
| #9 invoice flagged and payment held until the vendor is approved | Tested |
| #9 bank change: Clerk/Admin requests, a different FM/Admin verifies, old version kept, payment allowed after | Tested (Cascadia BC-009) |
| #9 callback on the number on file | Partly: a written callback note is required; storing a phone number on file is out of scope (agreed with the team) |

## What this does not do (say it plainly if asked)

- **It can't stop someone who controls the database.** It makes their edits detectable, not impossible. In production,
  database access would go through an approval tool (a second person approves), every database session would be
  recorded off the server (e.g. pgAudit), and the HMAC key would be held by a different team.
- **It doesn't say who tampered.** The alert names the rows and the time; the database's own access logs name the person.
- **Anyone with database access can set `app.allow_reset` / `app.allow_reseal` themselves**, just as they can disable
  the triggers. The real protection is the HMAC key, which the database doesn't hold.
- **Re-sealing accepts the edited data.** It doesn't restore the original; only a backup can. A deleted row leaves an id
  gap that re-sealing cannot fix, so the chain stays red.
- **No phone number on file.** Out of scope; the callback is evidenced by the required note instead.
- **Approving a new vendor still auto-verifies its first bank account** (copied from the invoice) without a callback.
  This was there before; a callback checkbox on Approve vendor would fix it.
- **One admin can still change users' roles and limits, and the approval policies, alone.** A maker-checker step (a
  second admin approves) would close this; estimated 2 to 2.5 hours for user changes, frontend included.
