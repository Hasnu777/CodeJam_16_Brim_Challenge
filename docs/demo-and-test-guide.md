# Demo and test guide (22 Nov)

Two parts: a full manual test pass of the app (about 45 minutes), then the demo script for the 7-minute slot
(4:30 of demo, the rest is Q&A).

Before either part, reset the data and process the inbox:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\reset-and-seed.ps1
```

Then sign in as Jordan Okafor (AP Clerk), open the Inbox and click **Process Inbox**. Or run
`python scripts/verify_mess_table.py --process` to do the reset and processing and also check every messy case.

The app runs at http://localhost:5173. There are no passwords: pick a user in the switcher at the top right.
AP ids (AP-00427…) change between resets, so find bills by vendor name using Search.

## Part 1: full test pass

Already automated, and passing: `verify_mess_table.py` checks 24 cases and `verify_approvals_payments.py` checks 26
(both rerun on 22 Nov). The manual pass below covers what scripts cannot: what each role sees and whether the screens
make sense.

### 1. Kavya Morrison (Employee, Maintenance Technician)

- [ ] The header reads "Kavya Morrison · Maintenance Technician (Employee)". The sidebar shows no role label and the
      nav is in Title Case.
- [ ] Home: the upload card reads "Upload PDF or accepted photo types (.jpg, .jpeg, .png)". Upload any PDF from
      `dataset_given/incoming_invoices` with **Upload Invoice**. It appears under My Submissions.
- [ ] Open it, then **Delete** with a reason. You go back Home, and the bill is gone.
- [ ] Open the Inbox, Payments and Audit pages by typing their URLs: no data shows, because the server refuses it
      (403).

### 2. Jordan Okafor (AP Clerk), features 1–5

- [ ] Home: the Blocked, Attention Needed and Approved cards, "N emails waiting in inbox", and the Monthly Spend chart
      with short month names.
- [ ] Inbox:
  - [ ] Filter **Needs Attention**. You should see the 4 non-invoice emails (newsletter, payment question, supplier
        statement, "invoice attached" with nothing attached), each with a Summary explaining why.
  - [ ] Open one and click **Reply**. Save Draft, then Send. Both appear in the email's History, and the pop-up says
        nothing is actually sent.
- [ ] Upload the Laurentian French invoice (`laurentianlogist_FA002399.pdf`; delete its inbox bill first, or it is
      flagged as a duplicate):
  - [ ] Amounts like `1 234,56 $` are normalised, and TPS/TVQ map to the GST/QST tax codes.
  - [ ] Suggested Entries have a **Why?** tooltip ("N of M past invoices…"). Change one GL account: the change shows
        in History.
- [ ] Invoices: turn on Flagged Only, then sort by Vendor and by Due Date. Check each case below:

  | Vendor | Expected |
  | --- | --- |
  | Cascadia Cartage (both bills) | Blocking issue: bank changed 27 Oct by email and never verified |
  | Harlow **and** Stern LLP | Lookalike of Harlow & Stern: different GST #, bank and domain |
  | Polar 000961, Harlow & Stern 001468, Cloudbarn INV-01561 | Duplicate (already in history) |
  | Blue Heron, Zephyr Data | Not in vendor master |
  | Riverbend Catering | Vendor pending review |
  | Beaumont Rivière | Quebec delivery charged without QST |
  | Orion Strategy (3 bills) | Split-invoice issue, combined $14,543.10 |
  | Fixwell, Great Lakes | Credit notes |
  | Sparkgrid, Tessera | USD shown as "USD $…", with the CAD value and FX rate |

- [ ] On an invoice page:
  - [ ] Acknowledge one warning. The note reads "Issue acknowledged".
  - [ ] Submit the Orion bill. The **Approval Chain** tab shows Department Manager + Finance Manager.
- [ ] No rule codes (V01, D01, P13…) or person, department or email ids appear anywhere. AP ids, payment ids, GL
      numbers and tax codes are expected.

### 3. Approvers, feature 6

- [ ] Elise Tremblay (Finance Manager):
  - [ ] The header shows "Delegate For Liam O'Connor".
  - [ ] My Approvals lists Facilities bills on Liam's behalf. Approve one: its Approval Chain says "on behalf of Liam
        O'Connor".
- [ ] Dev Malhotra (Operations Manager): Reject a bill with a reason. Jordan (Clerk) reopens it and resubmits, which
      starts a new round.
- [ ] A bill over $50k adds the Controller; one over $250k adds the CFO.

### 4. Payments, feature 7 plus Brim card

- [ ] Jordan (Clerk): select approved bills in Invoices, then **Prepare Payments**. They appear as Scheduled with a
      date set from the vendor's terms.
- [ ] Elise: the payment for a bill she approved has **Release** disabled with the reason shown. She can release
      the others.
- [ ] Brim Card Savings shows three numbers, and the tooltip explains the assumptions.
- [ ] Switch a card-accepting vendor's payment to Card. After release, use **Test Card**:
  - [ ] Over the limit: declined.
  - [ ] Another merchant: declined.
  - [ ] The exact amount: approved.
  - [ ] A second charge: declined.
- [ ] **Create Payment Batch**:
  - [ ] Cancel: nothing changes and the button stays.
  - [ ] Create again, then **Download CSV and Send**: the file downloads, the payments become Sent, and each row has
        a "Batch EFT-000N (CSV)" link.
- [ ] Settle one payment and mark another as failed. The failed one goes back to Scheduled, gets a retry, and its
      old card is cancelled.

### 5. Marcus Webb (Controller/Admin), features 6, 8 and 9

- [ ] Policies:
  - [ ] Change the $10,000 threshold and preview a bill's routing before and after.
  - [ ] Save, then change it back.
- [ ] Vendors:
  - [ ] Onboard a new vendor. It is Pending until the Finance Manager approves, and its bills are held until then.
  - [ ] Request a bank change. Payments are held until a second person verifies it by callback.
- [ ] Spend Analytics: the chart fits on screen. Click a vendor or category row: Invoices opens with exactly those
      bills and a blue banner.
- [ ] Reconciliation: matched and unmatched statement lines, each with a likely explanation.

### 6. Hannah Lindqvist (Auditor), feature 8

- [ ] Audit Log: filter by one payment's id and follow it from intake through coding, approvals, release and batch.
- [ ] **Verify Chain** reports the chain is valid. Export the CSV.
- [ ] Every button that would change something is missing or refused. The server returns 403 for any
      POST/PUT/DELETE.

## Part 2: the 7-minute slot

The slot is **4:30 of demo, 30 s of buffer and at least 2:00 of Q&A**. Two people present: one drives the keyboard and
one narrates. Never type during the demo; prepare everything in advance.

### Prep (do it 30 minutes before, then rehearse once on this exact data)

1. Reset, then **Process Inbox** as Jordan.
2. As Jordan, delete the Laurentian bill (reason "demo prep"). Keep `laurentianlogist_FA002399.pdf` ready in the file
   picker for the live upload.
3. Pick one Facilities bill under $10k and submit it. As Elise, approve it (on Liam's behalf). As Jordan, prepare its
   payment. Note its payment id for the 403 call.
4. Leave the Orion bills unsubmitted.
5. Open tabs or keep these ready:
   - the Fraud Alerts page;
   - a PowerShell window with the 403 call typed in (below);
   - the user switcher.
6. Turn off notifications and set browser zoom to about 110%.

The 403 call (replace the payment id):

```powershell
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession; Invoke-RestMethod -Method Post http://localhost:8088/api/auth/login -WebSession $s -ContentType 'application/json' -Body '{"userId":"U003"}' | Out-Null; try { Invoke-RestMethod -Method Post http://localhost:8088/api/payments/PAY-00000/release -WebSession $s -ContentType 'application/json' -Body '{}' } catch { "HTTP " + [int]$_.Exception.Response.StatusCode }
```

### Script

| Time | Signed in as | Show | Say |
| --- | --- | --- | --- |
| 0:00 | none | Title | "Harborline pays about 50 invoices a month out of an email inbox. We take each one from inbox to payment, with the right approvals and an audit trail a CRA auditor can verify." |
| 0:15 | Jordan, AP Clerk | Inbox, filter Needs Attention | "59 emails processed. The newsletter, the payment question and the 'invoice attached' email with no attachment became tasks with a reason, not bills." |
| 0:35 | Jordan | Upload the Laurentian PDF, then open it | "French invoice: `1 234,56 $` normalised, TPS and TVQ mapped to tax codes, and every line coded with a reason (hover Why?). The AI only reads the page; everything after this is tested Java." |
| 1:05 | Jordan | Fraud Alerts, open Cascadia | "The star case. The vendor master looks fine, but its bank account was changed on 27 Oct by email and never verified by phone. Both bills pay to it, so payment is blocked. Next to it, 'Harlow **and** Stern' imitates our real law firm: different GST number, bank and domain." |
| 1:40 | Jordan | Orion bill, Submit, Approval Chain tab | "Three consulting bills on the same day, each under the $10k limit. We route on the combined $14,543, so the Finance Manager is added. Splitting to stay under a limit doesn't work." |
| 2:05 | Elise, Finance Manager | Header "Delegate For Liam O'Connor", then the prepped bill's Approval Chain | "Liam is on leave; Elise approves on his behalf, and it's recorded that way." |
| 2:20 | Elise | Payments: Release disabled, with the reason; then the PowerShell window | "She approved it, so she can't release it. And it's not just a hidden button." Run the call: **HTTP 403**. |
| 2:45 | Marcus, Controller | Payments: Brim Card Savings; switch a payment to Card, release it, Test Card over the limit | "Card-accepting vendors can be paid by Brim card: cash stays in the bank until the statement is due, and we earn 1%. Each card payment gets a single-use virtual card locked to that vendor and that amount. An overcharge is declined." |
| 3:20 | Marcus | Create Payment Batch, preview, Download CSV and Send | "Nothing is sent until the file is downloaded, so you can wait for more releases." |
| 3:40 | Hannah, Auditor | Audit Log filtered to that payment, then Verify Chain | "Every step: who, when, on whose behalf, before and after. The log is hash-chained, so editing a row breaks the chain. The auditor can read everything and change nothing." |
| 4:05 | none | Close | "About 25 rules, all deterministic. 99.7% field accuracy on the 56 test invoices without an AI key, and 50 scripted edge cases passing." |
| 4:30 | | **Q&A** | |

**If you run long:**
- Cut the Brim card segment (2:45–3:20) first; it is a bonus.
- Then cut Harlow and Stern down to the single sentence.
- Never cut Cascadia, the 403, or the audit chain.

**If something breaks live:** stay on the current screen and tell it from what is visible. Record a backup video of
this exact run beforehand.

### Q&A: have these ready (full list in docs/PRD.md, section "Q&A prep")

- **How are roles enforced?** `@PreAuthorize` on every endpoint, and segregation-of-duties checks inside the
  workflow service's transaction. You just saw the 403.
- **Duplicates?** A weighted score over vendor and bank account, normalised invoice #, amount tolerance, date window,
  file hash, and Brim card transactions.
- **Bad input?** Schema-checked JSON, cross-checked arithmetic and per-field confidence. Garbage gives a
  manual-entry draft, never a crash.
- **At 100× volume?** Move the AI calls to a job queue, index duplicates on (vendor, normalised #), partition the
  audit log by month, and use read replicas for analytics.
- **Why can a bill be deleted if the log is append-only?** Only unapproved bills with no payment can be deleted.
  The log keeps an `INVOICE_DELETED` row with the bill's last snapshot and the reason.
- **Is the card real?** No. It's a simulated issuer, but the controls (limit, merchant lock, single use, expiry) are
  enforced and logged.
- **What did AI write?** Be honest about it, and make sure every owner can walk through their own code.
