#!/usr/bin/env python3
"""Check that every row of the PRD's "Mess found in the incoming batch" table gets its expected outcome.

Run after a fresh reset + "Process inbox" (or pass --process to do both):
    python scripts/verify_mess_table.py --process
Each row prints PASS/FAIL with the evidence; the exit code is the number of failures.
Also drives one clean invoice all the way to PAID and checks the audit chain.
"""
import argparse
import http.cookiejar
import json
import sys
import time
import urllib.error
import urllib.request

API = "http://localhost:8088/api"


class Client:
    def __init__(self, user):
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.call("POST", "/auth/login", {"userId": user})

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(API + path, data=data, method=method, headers={"Content-Type": "application/json"})
        try:
            raw = self.op.open(req, timeout=600).read()
            return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            return {"_status": e.code, **(json.loads(e.read() or b"{}"))}

    def get(self, path):
        return self.call("GET", path)

    def post(self, path, body=None):
        return self.call("POST", path, body or {})


results = []


def check(row, ok, evidence):
    results.append((row, bool(ok), evidence))
    print(f"[{'PASS' if ok else 'FAIL'}] {row}\n        {evidence}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--process", action="store_true", help="reset the database and process the inbox first")
    args = ap.parse_args()
    admin, clerk = Client("U002"), Client("U004")
    if args.process:
        admin.post("/admin/reset")
        admin, clerk = Client("U002"), Client("U004")
        clerk.post("/inbox/process")
        while clerk.get("/inbox/status")["running"]:
            time.sleep(1.5)

    invoices = clerk.get("/invoices")
    by_num = {i["vendorInvoiceNumber"]: i for i in invoices}

    def flags(num):
        inv = by_num.get(num)
        if not inv:
            return None, []
        d = clerk.get(f"/invoices/{inv['id']}")
        return d, [f for f in d["flags"] if f["status"] == "OPEN"]

    def codes(num):
        return sorted({f["ruleCode"] for f in flags(num)[1]})

    def preview(num):
        return admin.post("/policies/preview", {"invoiceId": by_num[num]["id"]})["current"]

    # 1. Cascadia: bank changed by email, unverified -> payment hold + fraud alert (both invoices)
    for num in ("1624", "1625"):
        d, fl = flags(num)
        msg = next((f["message"] for f in fl if f["ruleCode"] == "B02"), "")
        check(f"Cascadia {num}: unverified email bank change -> B02 + B01 (payment hold)",
              {"B01", "B02"} <= {f["ruleCode"] for f in fl} and "9679" in msg, msg[:140])

    # 2. Harlow and Stern lookalike -> V04 + Legal extra approval step
    d, fl = flags("HA26-2766")
    v04 = next((f["message"] for f in fl if f["ruleCode"] == "V04"), "")
    pv = preview("HA26-2766")
    check("Harlow and Stern HA26-2766: lookalike vendor V052 vs V026 -> V04 BLOCK + Legal extra step (P06)",
          "V026" in v04 and d["invoice"]["vendorId"] == "V052" and "P06" in pv["matchedPolicies"],
          f"{v04[:120]} | policies {pv['matchedPolicies']}")

    # 3. Exact duplicates of paid history
    for num in ("000961", "001468", "INV-01561"):
        d, fl = flags(num)
        check(f"Invoice {num}: already in history -> D01 + DUPLICATE",
              "D01" in {f["ruleCode"] for f in fl} and d["invoice"]["status"] == "DUPLICATE",
              next((f["message"] for f in fl if f["ruleCode"] == "D01"), "")[:120])

    # 4. Unknown vendors -> hold + onboarding
    for num, name in (("4443", "Blue Heron"), ("7590", "Zephyr")):
        d, fl = flags(num)
        pv = preview(num)
        check(f"{name} {num}: not in vendor master -> V01 hold (+ FM step P05)",
              d["invoice"]["vendorId"] is None and "V01" in {f["ruleCode"] for f in fl} and "P05" in pv["matchedPolicies"],
              f"vendorId={d['invoice']['vendorId']} flags={sorted({f['ruleCode'] for f in fl})} policies={pv['matchedPolicies']}")

    # 5. Riverbend pending_review, no GST -> new-vendor approval step P05
    pv = preview("1739")
    check("Riverbend 1739: vendor pending_review -> V02 hold + new-vendor step (P05 -> finance_manager)",
          "V02" in codes("1739") and "P05" in pv["matchedPolicies"] and any(s["role"] == "finance_manager" for s in pv["steps"]),
          f"flags={codes('1739')} steps={[(s['role'], s['assigneeId']) for s in pv['steps']]}")

    # 6. Beaumont: QC legal services charged GST only
    t01 = next((f["message"] for f in flags("FA-000557")[1] if f["ruleCode"] == "T01"), "")
    check("Beaumont FA-000557: QC delivery charged GST only -> T01", "QST $769.66" in t01, t01)

    # 7. Orion split: 279/281/287 each < 10k, combined 14,543.10 -> S01, routed at combined (+FM)
    ok = True
    ev = []
    for num in ("279", "281", "287"):
        s01 = next((f for f in flags(num)[1] if f["ruleCode"] == "S01"), None)
        pv = preview(num)
        ok &= s01 is not None and "14,543.10" in s01["message"] and float(pv["amountCad"]) == 14543.10 \
            and [s["role"] for s in pv["steps"]] == ["dept_manager", "finance_manager"]
        ev.append(f"{num}: routed at {pv['amountCad']} -> {[s['role'] for s in pv['steps']]}")
    check("Orion 279/281/287: split under $10,000 -> S01, routed at $14,543.10 (adds Finance Manager)", ok, "; ".join(ev))

    # 8. Credit notes
    for num in ("CN-FA-000413", "CN-GR26-2274"):
        d, fl = flags(num)
        check(f"Credit note {num}: negative total -> X05 credit handling",
              d["invoice"]["creditNote"] and float(d["invoice"]["total"]) < 0 and "X05" in {f["ruleCode"] for f in fl},
              next((f["message"] for f in fl if f["ruleCode"] == "X05"), "")[:140])

    # 9. USD vendors: FX conversion + FOREIGN tax code
    for num, name in (("FA-002342", "Sparkgrid"), ("002715", "Tessera")):
        d, fl = flags(num)
        inv = d["invoice"]
        check(f"{name} {num}: USD, US vendor -> FX1 conversion + FOREIGN tax code, no Canadian tax",
              inv["currency"] == "USD" and "FX1" in {f["ruleCode"] for f in fl}
              and all(line["gstHstCode"] == "FOREIGN" for line in d["lines"]) and not d["taxes"],
              next((f["message"] for f in fl if f["ruleCode"] == "FX1"), ""))

    # 10. French invoices: 1 234,56 $ + TPS/TVQ
    for num, total in (("FA-002399", "5015.98"), ("FA-000566", "6990.48")):
        d, _ = flags(num)
        tax = sorted(t["taxCode"] for t in d["taxes"])
        check(f"French invoice {num}: '1 234,56 $' and TPS/TVQ normalized",
              d["invoice"]["total"] == total and tax == ["GST5", "QST9975"], f"total={d['invoice']['total']} taxes={tax}")

    # 11. Emails without attachments
    emails = {e["id"]: e for e in clerk.get("/inbox")}
    for eid, code in (("EM-058", "E01"), ("EM-057", "E02"), ("EM-056", "E03"), ("EM-059", "E04")):
        e = emails[eid]
        check(f"{eid} '{e['subject']}' -> {code}", e["intakeCode"] == code and e["classification"] != "INVOICE",
              f"{e['classification']} by {e['classifiedBy']}: {e['reason'][:110]}")

    # 12. Delegation (U012 on leave -> U003) and terminated U015
    facilities = next(i for i in invoices if i["departmentId"] == "D07" and i["status"] == "IN_REVIEW")
    pv = admin.post("/policies/preview", {"invoiceId": facilities["id"]})["current"]
    s1 = pv["steps"][0]
    term = Client.__new__(Client)
    term.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    login = term.call("POST", "/auth/login", {"userId": "U015"})
    check("U012 on leave -> delegate U003 recorded; U015 terminated never routed or logged in",
          s1["assigneeId"] == "U003" and s1["onBehalfOfId"] == "U012" and login.get("_status") == 403,
          f"{facilities['id']} step1 {s1['assigneeId']} on behalf of {s1['onBehalfOfId']}; U015 login -> {login.get('_status')} {login.get('reason')}")

    # 13. One clean bill end to end: submit -> approve -> prepare -> release -> EFT -> settle -> PAID
    clean = by_num.get("002935")  # Queen Street, D07, $18,758: the PRD's worked example
    iid = clean["id"]
    clerk.post(f"/invoices/{iid}/submit")
    elise, marcus, priya = Client("U003"), Client("U002"), Client("U001")
    elise.post(f"/invoices/{iid}/approve", {"comment": "lease"})
    marcus.post(f"/invoices/{iid}/approve", {"comment": "ok"})
    pay = clerk.post("/payments/prepare", {"invoiceIds": [iid]})[0]["id"]
    refused = [elise.post(f"/payments/{pay}/release").get("_status"), marcus.post(f"/payments/{pay}/release").get("_status")]
    priya.post(f"/payments/{pay}/release")
    priya.post("/payments/batch")
    priya.post(f"/payments/{pay}/settle")
    final = clerk.get(f"/invoices/{iid}")
    check("Worked example 002935 end to end: delegate + escalation, approvers refused release (403), CFO releases -> PAID",
          final["invoice"]["status"] == "PAID" and refused == [403, 403],
          f"status={final['invoice']['status']} approver release attempts={refused}")

    v = admin.get("/audit/verify")
    check("Audit chain verifies after the whole run", v["valid"], f"{v['events']} events, head {str(v['headHash'])[:16]}")

    fails = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(fails)}/{len(results)} checks passed")
    sys.exit(len(fails))


if __name__ == "__main__":
    main()
