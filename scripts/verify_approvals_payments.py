#!/usr/bin/env python3
"""Live edge-case checks for features 6 (approval workflow) and 7 (payments), against the running app.

    python scripts/verify_approvals_payments.py          # reset, process the inbox, run every scenario, reset again

Each scenario prints PASS/FAIL with evidence; the exit code is the number of failures. It changes data (terminates a
user, edits invoice totals), so it always finishes with a reset.
"""
import http.cookiejar
import json
import sys
import threading
import time
import urllib.error
import urllib.request

API = "http://localhost:8088/api"
results = []


class Client:
    def __init__(self, user):
        self.user = user
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.call("POST", "/auth/login", {"userId": user})

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(API + path, data=data, method=method, headers={"Content-Type": "application/json"})
        try:
            raw = self.op.open(req, timeout=600).read()
            out = json.loads(raw) if raw else {}
            return {"_status": 200, **out} if isinstance(out, dict) else out
        except urllib.error.HTTPError as e:
            return {"_status": e.code, **(json.loads(e.read() or b"{}"))}

    def get(self, path):
        return self.call("GET", path)

    def post(self, path, body=None):
        return self.call("POST", path, body or {})

    def put(self, path, body):
        return self.call("PUT", path, body)


def check(name, ok, evidence):
    results.append((name, bool(ok)))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}\n        {evidence}")


def reset_and_process():
    admin = Client("U002")
    admin.post("/admin/reset")
    clerk = Client("U004")
    clerk.post("/inbox/process")
    while clerk.get("/inbox/status")["running"]:
        time.sleep(1.5)


def detail(c, iid):
    return c.get(f"/invoices/{iid}")


def current_round(d):
    return [s for s in d["approvalSteps"] if s["decision"] != "CANCELLED" and not s.get("superseded") and not s["historical"]]


def make_submittable(clerk, iid):
    """Acknowledge every open WARN/INFO flag; return the open non-hold BLOCK flags (which stop submission)."""
    d = detail(clerk, iid)
    blocks = []
    for f in d["flags"]:
        if f["status"] != "OPEN":
            continue
        if f["severity"] == "BLOCK":
            if f["ruleCode"] not in ("V01", "V02", "B01", "B02"):
                blocks.append(f["ruleCode"])
            continue
        clerk.post(f"/flags/{f['id']}/resolve", {"resolution": "ACKNOWLEDGED", "note": "checked (edge-case script)"})
    return blocks


def clean_candidates(clerk, dept=None, max_cad=None):
    """IN_REVIEW bills from an active vendor with verified bank details and no hold or block flags."""
    out = []
    for inv in clerk.get("/invoices"):
        if inv["status"] != "IN_REVIEW" or inv.get("creditNote") or not inv.get("departmentId"):
            continue
        if dept and inv["departmentId"] != dept:
            continue
        if max_cad and float(inv["totalCad"] or 0) > max_cad:
            continue
        d = detail(clerk, inv["id"])
        codes = {f["ruleCode"] for f in d["flags"] if f["status"] == "OPEN"}
        if any(f["severity"] == "BLOCK" for f in d["flags"] if f["status"] == "OPEN") or codes & {"V01", "V02", "B01", "B02"}:
            continue
        out.append(inv["id"])
    return out


def submit_first(clerk, candidates, prepare=None):
    for iid in candidates:
        if prepare:
            prepare(iid)
        if make_submittable(clerk, iid):
            continue
        r = clerk.post(f"/invoices/{iid}/submit")
        if r.get("_status") == 200:
            return iid
    return None


def audit(admin, entity_id, action=None):
    q = f"/audit?entityId={entity_id}" + (f"&action={action}" if action else "")
    return admin.get(q)


def main():
    reset_and_process()
    clerk, elise, marcus, priya = Client("U004"), Client("U003"), Client("U002"), Client("U001")

    # 1. Reject -> reopen -> resubmit: a fresh round, the same people may approve again (gap 1)
    iid = "AP-" + next(i["id"][3:] for i in clerk.get("/invoices") if i["vendorInvoiceNumber"] == "002935")
    make_submittable(clerk, iid)
    s1 = clerk.post(f"/invoices/{iid}/submit")
    a1 = elise.post(f"/invoices/{iid}/approve", {"comment": "lease ok"})
    rj = marcus.post(f"/invoices/{iid}/reject", {"reason": "wrong department, please check"})
    early_reopen_by_approver = elise.post(f"/invoices/{iid}/reopen", {"reason": "x"})
    ro = clerk.post(f"/invoices/{iid}/reopen", {"reason": "department confirmed with Facilities"})
    s2 = clerk.post(f"/invoices/{iid}/submit")
    a2 = elise.post(f"/invoices/{iid}/approve", {"comment": "round 2"})
    a3 = marcus.post(f"/invoices/{iid}/approve", {"comment": "round 2"})
    d = detail(clerk, iid)
    superseded = [s for s in d["approvalSteps"] if s.get("superseded")]
    check("Reject -> reopen -> resubmit starts a fresh round; Elise and Marcus can approve again",
          [s1["_status"], a1["_status"], rj["_status"], ro["_status"], s2["_status"], a2["_status"], a3["_status"]] == [200] * 7
          and d["invoice"]["status"] == "APPROVED" and len(superseded) == 2 and len(current_round(d)) == 2,
          f"status={d['invoice']['status']} superseded={len(superseded)} round2={[(s['assignedUserId'], s['decision']) for s in current_round(d)]}")
    check("Only the clerk or an Admin can reopen; an approver gets 403",
          early_reopen_by_approver["_status"] == 403, f"Elise reopen -> {early_reopen_by_approver['_status']}")
    pay = clerk.post("/payments/prepare", {"invoiceIds": [iid]})
    pid = pay[0]["id"]
    rel = [elise.post(f"/payments/{pid}/release")["_status"], marcus.post(f"/payments/{pid}/release")["_status"]]
    check("Approvers from both rounds still can't release (P11); the CFO can",
          rel == [403, 403] and priya.post(f"/payments/{pid}/release")["_status"] == 200, f"Elise, Marcus -> {rel}")

    # 2. Self-approval by proxy: Liam (on leave, delegate Elise) requests a Facilities bill himself (gap 2)
    cands = clean_candidates(clerk, dept="D07", max_cad=9000)
    proxy = submit_first(clerk, cands, prepare=lambda i: clerk.put(f"/invoices/{i}", {"requesterUserId": "U012"}))
    if proxy:
        st = current_round(detail(clerk, proxy))[0]
        check("Liam requests a D07 bill: it goes to his manager (Priya), not to Elise on his behalf",
              st["assignedUserId"] == "U001" and st["onBehalfOfUserId"] is None, f"{proxy} step1 -> {st['assignedUserId']} on behalf of {st['onBehalfOfUserId']}")
    else:
        check("Liam requests a D07 bill: it goes to his manager", False, f"no clean D07 bill under $9k to test with ({cands})")

    # 3. Stuck step: the approver is terminated after routing -> Admin re-routes (gap 3)
    stuck = submit_first(clerk, clean_candidates(clerk, dept="D01", max_cad=9000))
    if stuck:
        before = current_round(detail(clerk, stuck))[0]["assignedUserId"]
        not_admin = elise.post(f"/invoices/{stuck}/reroute", {"reason": "x"})
        same = marcus.post(f"/invoices/{stuck}/reroute", {"reason": "nothing changed yet"})
        marcus.put("/users/U007", {"status": "terminated"})
        rr = marcus.post(f"/invoices/{stuck}/reroute", {"reason": "Dev left the company"})
        after = current_round(detail(clerk, stuck))
        ap = priya.post(f"/invoices/{stuck}/approve", {"comment": "covering for Operations"})
        check("Approver terminated after routing: Admin re-routes the pending step to their manager, who approves",
              before == "U007" and rr["_status"] == 200 and after[0]["assignedUserId"] == "U001" and ap["_status"] == 200,
              f"{stuck}: {before} -> {after[0]['assignedUserId']}; approve -> {ap['_status']}")
        check("Re-route is Admin-only, and refuses when nothing would change",
              not_admin["_status"] == 403 and same["_status"] == 409, f"Elise -> {not_admin['_status']}; no-op -> {same['_status']}: {same.get('reason', '')[:80]}")
        double_pay_invoice = stuck
    else:
        check("Re-route a stuck step", False, "no clean D01 bill under $9k to test with")
        double_pay_invoice = None

    # 4. Two people release the same payment at the same moment: exactly one release (gap 4)
    if double_pay_invoice:
        pid = clerk.post("/payments/prepare", {"invoiceIds": [double_pay_invoice]})[0]["id"]
        barrier, codes = threading.Barrier(2), {}

        def go(c):
            barrier.wait()
            codes[c.user] = c.post(f"/payments/{pid}/release")["_status"]

        ts = [threading.Thread(target=go, args=(c,)) for c in (elise, marcus)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        released_rows = [e for e in audit(marcus, pid, "PAYMENT_RELEASED")]
        check("Concurrent release by two people: one succeeds, the other gets 409, one audit row",
              sorted(codes.values()) == [200, 409] and len(released_rows) == 1, f"{pid}: {codes}; PAYMENT_RELEASED rows={len(released_rows)}")

    # 5. Over $250k: dept -> FM -> Controller -> CFO; only the CFO may release, logged as an SoD exception (D-082)
    big, why_not = None, []
    for i in clean_candidates(clerk):
        clerk.put(f"/invoices/{i}", {"total": "300000.00", "subtotal": "300000.00", "gstHstAmount": "0.00", "pstQstAmount": "0.00"})
        blocks = make_submittable(clerk, i)
        r = {"_status": 0, "reason": f"blocks {blocks}"} if blocks else clerk.post(f"/invoices/{i}/submit")
        if r["_status"] == 200:
            big = i
            break
        why_not.append(f"{i}: {r.get('reason', '')[:90]}")
    if big:
        chain = current_round(detail(clerk, big))
        dev_or_mgr = Client(chain[0]["assignedUserId"])
        for c in (dev_or_mgr, elise, marcus, priya):
            c.post(f"/invoices/{big}/approve", {"comment": "big one"})
        st = detail(clerk, big)["invoice"]["status"]
        pid = clerk.post("/payments/prepare", {"invoiceIds": [big]})[0]["id"]
        refused = [elise.post(f"/payments/{pid}/release")["_status"], marcus.post(f"/payments/{pid}/release")["_status"]]
        ok = priya.post(f"/payments/{pid}/release")["_status"]
        exc = audit(marcus, pid, "SOD_EXCEPTION")
        check("$300k bill: 4-step chain ending at the CFO; FM and Controller refused; CFO releases with a SOD_EXCEPTION audit row",
              [s["requiredRole"] for s in chain] == ["dept_manager", "finance_manager", "controller_admin", "cfo"] and st == "APPROVED"
              and refused == [403, 403] and ok == 200 and len(exc) == 1,
              f"{big}: chain={[s['assignedUserId'] for s in chain]} status={st} FM/Controller={refused} CFO={ok} exceptions={len(exc)}")
    else:
        check("$300k bill CFO exception", False, "no bill could be raised to $300k and submitted: " + "; ".join(why_not[:4]))

    v = marcus.get("/audit/verify")
    check("Audit chain still verifies", v["valid"], f"{v['events']} events")

    Client("U002").post("/admin/reset")
    fails = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(fails)}/{len(results)} checks passed (database reset afterwards; click Process inbox to reload the batch)")
    sys.exit(len(fails))


if __name__ == "__main__":
    main()
