# Brim Card Payment Optimizer

Bonus 5 · Brim AP Automation (CodeJam) · Nov 22, 2026

Harborline pays 3 card-accepting vendors by EFT. Paying them on the Brim AP card would keep cash in the bank for about 35 more days and earn rewards. Over 10 months of history, that left **about $1,338 on the table** across 26 payments ($89,938).

## How it works

A pure, unit-tested Java function (no AI) checks each scheduled payment in this order:

1. Vendor doesn't accept card → no advice.
2. Not CAD, or over $50,000 → no card advice.
3. Already on card → shows what it earns.
4. Otherwise → recommends "Pay by Brim card".

The card is charged on the scheduled date and is due 21 days after the month-end statement. Benefit = amount × 5%/yr × days kept + 1% rewards. Example: $10,000 on Nov 10 is due Dec 21 (41 days): $56.16 float + $100 rewards = **$156.16**. Early-pay discounts are kept.

## In the app

- **Payments page panel:** missed, captured and still-switchable card benefit.
- **Badge per scheduled payment** ("+$13.24 · 45d"); hover for the arithmetic.
- **"Pay by Brim card" / "Use EFT" button** for Clerk, Finance Manager and Admin; the Auditor sees, but can't switch.
- **Server-enforced:** `POST /api/payments/{id}/method` refuses vendors that don't accept card (409), bad methods (400) and wrong roles (403). Every switch writes a `PAYMENT_METHOD_CHANGED` audit event. Release rules are unchanged.

## Results

| Group | Payments | Amount | Benefit |
| --- | --- | --- | --- |
| Paid by EFT, could have been card | 26 | $89,938 | $1,338 |
| Already on Brim card | 32 | $71,570 | $1,094 |

Most of the missed benefit is Trailhead Fuel ($866), Alpenglow Events ($320) and Paper Trail Office ($152).

## Assumptions

The 21-day grace period, 5% value of cash, 1% rewards and $50,000 limit are our assumptions, printed on the page (D-085). Not modelled: vendor card surcharges and a total credit limit.

## Demo

As the AP Clerk, open Payments, point at the $1,338, hover a purple badge, switch a payment to EFT and back, then show the two audit events as the Auditor.
