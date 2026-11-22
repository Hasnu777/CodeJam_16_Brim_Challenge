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


# ---------- shared helpers for the edge cases (scenarios 6-12) ----------

_clients = {}


def as_user(uid):
    if uid not in _clients:
        _clients[uid] = Client(uid)
    return _clients[uid]


def approve_all(iid, comment="edge-case script"):
    """Each pending step is approved by its assignee (the delegate, if there is one). Returns the final status."""
    clerk = as_user("U004")
    for _ in range(8):
        pending = [s for s in current_round(detail(clerk, iid)) if s["decision"] == "PENDING"]
        if not pending:
            break
        as_user(pending[0]["assignedUserId"]).post(f"/invoices/{iid}/approve", {"comment": comment})
    return detail(clerk, iid)["invoice"]["status"]


def release_by_anyone(pid):
    """First of FM, Controller, CFO that is allowed to release (P11 rules out the approvers)."""
    for uid in ("U003", "U002", "U001"):
        if as_user(uid).post(f"/payments/{pid}/release")["_status"] == 200:
            return uid
    return None


def payment_rows(c):
    return c.get("/payments?includeHistory=true")


def payments_for(c, iid):
    return [r["payment"] for r in payment_rows(c) if r["payment"]["invoiceId"] == iid]


def submit_with_total(clerk, total, currency=None, dept=None, exclude=()):
    """Set a clean bill's total (no tax, so subtotal = total), acknowledge its flags and submit it. Returns the invoice id."""
    for i in clean_candidates(clerk, dept=dept):
        if i in exclude:
            continue
        body = {"total": total, "subtotal": total, "gstHstAmount": "0.00", "pstQstAmount": "0.00"}
        if currency:
            body["currency"] = currency
        clerk.put(f"/invoices/{i}", body)
        if make_submittable(clerk, i):
            continue
        if clerk.post(f"/invoices/{i}/submit")["_status"] == 200:
            return i
    return None


def parallel(*calls):
    """Run the calls at the same instant (barrier); return their HTTP statuses in order."""
    barrier, out = threading.Barrier(len(calls)), [None] * len(calls)

    def go(k, f):
        barrier.wait()
        out[k] = f()["_status"]

    ts = [threading.Thread(target=go, args=(k, f)) for k, f in enumerate(calls)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    return out


def roles_of(clerk, iid):
    return [s["requiredRole"] for s in current_round(detail(clerk, iid))]


def scenario_boundaries(clerk):
    """6. Exact tier boundaries end to end: 'up to' includes the amount, 'over' starts one cent above."""
    used = []
    for total, want in (("10000.00", ["dept_manager"]), ("10000.01", ["dept_manager", "finance_manager"]),
                        ("50000.00", ["dept_manager", "finance_manager"]),
                        ("50000.01", ["dept_manager", "finance_manager", "controller_admin"])):
        iid = submit_with_total(clerk, total, exclude=used)
        if not iid:
            check(f"Bill of exactly CAD {total} routes to {want}", False, "no clean bill to test with")
            continue
        used.append(iid)
        inv = detail(clerk, iid)["invoice"]
        got = roles_of(clerk, iid)
        check(f"Bill of exactly CAD {total} routes to {' + '.join(want)}", got == want, f"{iid}: routed at {inv.get('routedAmountCad')} -> {got}")


def scenario_usd(clerk):
    """7. A USD bill under 10k that is over 10k in CAD."""
    iid = submit_with_total(clerk, "9000.00", currency="USD")
    if not iid:
        check("USD 9,000 bill routes on its CAD value", False, "no clean bill to test with")
        return
    inv = detail(clerk, iid)["invoice"]
    check("USD 9,000 bill is routed on its CAD value (over 10k) and gets the Finance Manager",
          inv["currency"] == "USD" and float(inv["totalCad"] or 0) > 10000 and roles_of(clerk, iid) == ["dept_manager", "finance_manager"],
          f"{iid}: USD {inv['total']} = CAD {inv['totalCad']} at {inv.get('fxRateToCad')} -> {roles_of(clerk, iid)}")


def scenario_delegation(clerk):
    """8. 'Delegate my approvals' through the API (T7's done-when), for an approver who is still active."""
    wen, elise = as_user("U010"), as_user("U003")
    year = {"start": "2026-01-01", "end": "2026-12-31"}
    other = wen.post("/delegations", {"fromUser": "U007", "toUser": "U003", **year})
    to_emp = wen.post("/delegations", {"fromUser": "U010", "toUser": "U019", **year})
    to_self = wen.post("/delegations", {"fromUser": "U010", "toUser": "U010", **year})
    check("Delegation guards: not for someone else (403), not to an employee or to yourself (4xx)",
          other["_status"] == 403 and 400 <= to_emp["_status"] < 500 and 400 <= to_self["_status"] < 500,
          f"for Dev -> {other['_status']}; to Stefan -> {to_emp['_status']}; to self -> {to_self['_status']}")
    made = wen.post("/delegations", {"fromUser": "U010", "toUser": "U003", "reason": "conference", **year})
    acting = [a["userId"] for a in elise.get("/auth/me").get("actingFor", [])]
    iid = submit_with_total(clerk, "900.00", dept="D05")
    if made["_status"] != 200 or not iid:
        check("Wen delegates to Elise: a new Marketing bill routes to Elise on Wen's behalf", False,
              f"delegation -> {made['_status']} {made.get('reason', '')}; bill -> {iid}")
        return
    st = current_round(detail(clerk, iid))[0]
    check("Wen (still active) delegates to Elise: a new Marketing bill routes to Elise on Wen's behalf",
          st["assignedUserId"] == "U003" and st["onBehalfOfUserId"] == "U010" and "U010" in acting,
          f"{iid}: step 1 -> {st['assignedUserId']} on behalf of {st['onBehalfOfUserId']}; Elise acting for {acting}")
    queued = [q for q in elise.get("/approvals/mine") if q["invoiceId"] == iid]
    ap = elise.post(f"/invoices/{iid}/approve", {"comment": "for Wen"})
    pid = clerk.post("/payments/prepare", {"invoiceIds": [iid]})[0]["id"]
    rel = [elise.post(f"/payments/{pid}/release")["_status"], as_user("U002").post(f"/payments/{pid}/release")["_status"]]
    said = any("on behalf of" in (e.get("detail") or "") for e in audit(as_user("U002"), iid))
    check("Elise sees it in My approvals and approves for Wen; she can't release it (P11), Marcus can; audit says 'on behalf of'",
          len(queued) == 1 and ap["_status"] == 200 and rel == [403, 200] and said,
          f"queue={len(queued)} approve={ap['_status']} release Elise/Marcus={rel} audit on-behalf={said}")


def scenario_double_approve(clerk):
    """9. Double-click Approve (two sessions of the same person at the same instant)."""
    iid = submit_with_total(clerk, "800.00")
    if not iid:
        check("Double-click Approve", False, "no clean bill to test with")
        return
    uid = current_round(detail(clerk, iid))[0]["assignedUserId"]
    a, b = as_user(uid), Client(uid)
    codes = parallel(lambda: a.post(f"/invoices/{iid}/approve", {"comment": "click"}), lambda: b.post(f"/invoices/{iid}/approve", {"comment": "click"}))
    rows = [e for e in audit(as_user("U002"), iid) if e["action"] in ("APPROVED", "APPROVAL_STEP_APPROVED")]
    check("Double-click Approve: one 200, one refusal, the step is approved once",
          sorted(codes)[0] == 200 and sorted(codes)[1] in (409, 422) and len(rows) == 1 and detail(clerk, iid)["invoice"]["status"] == "APPROVED",
          f"{iid}: {codes}; approval audit rows={len(rows)}")


def scenario_policy_edit(clerk):
    """10. The rules are edited while a bill is mid-chain: the bill keeps the chain it was routed with."""
    iid = submit_with_total(clerk, "12000.00")
    if not iid:
        check("Policy edit mid-chain", False, "no clean bill to test with")
        return
    admin = as_user("U002")
    before = admin.get("/policies")
    saved = admin.call("PUT", "/policies", [dict(p, enabled=False) if p["id"] == "P02" else p for p in before])
    first = current_round(detail(clerk, iid))[0]
    as_user(first["assignedUserId"]).post(f"/invoices/{iid}/approve", {"comment": "step 1"})
    mid = detail(clerk, iid)
    status_mid, chain = mid["invoice"]["status"], [s["requiredRole"] for s in current_round(mid)]
    final = approve_all(iid)
    admin.call("PUT", "/policies", before)
    restored = [p["enabled"] for p in admin.get("/policies") if p["id"] == "P02"] == [True]
    check("Rules edited mid-chain (P02 switched off): the bill keeps its routed chain and still needs the Finance Manager",
          isinstance(saved, list) and chain == ["dept_manager", "finance_manager"] and status_mid == "PENDING_APPROVAL" and final == "APPROVED" and restored,
          f"{iid}: edit saved={isinstance(saved, list)}; after step 1 status={status_mid} chain={chain}; final={final}; P02 restored={restored}")


def scenario_bank_change(clerk):
    """11. Vendor bank details change while the bill is in approval."""
    iid = submit_with_total(clerk, "700.00")
    if not iid:
        check("Bank change during approval", False, "no clean bill to test with")
        return
    vid = detail(clerk, iid)["invoice"]["vendorId"]
    bc = clerk.post(f"/vendors/{vid}/bank-change-requests",
                    {"institution": "003", "transit": "12345", "accountNumber": "9876543210", "channel": "phone", "note": "edge-case script"})
    status = approve_all(iid)
    pid = clerk.post("/payments/prepare", {"invoiceIds": [iid]})[0]["id"]
    held = [as_user(u).post(f"/payments/{pid}/release") for u in ("U003", "U002", "U001")]
    held_ok = all(h["_status"] != 200 for h in held) and any("waiting for verification" in h.get("reason", "") for h in held)
    own = clerk.post(f"/bank-change-requests/{bc.get('id')}/verify", {"callbackDone": True, "approve": True})
    no_call = as_user("U003").post(f"/bank-change-requests/{bc.get('id')}/verify", {"callbackDone": False, "approve": True})
    ver = as_user("U003").post(f"/bank-change-requests/{bc.get('id')}/verify", {"callbackDone": True, "approve": True, "note": "called the number on file"})
    check("Bank change while in approval: the payment is held until a second person verifies it by callback",
          bc.get("_status") == 200 and status == "APPROVED" and held_ok and own["_status"] == 403 and 400 <= no_call["_status"] < 500 and ver["_status"] == 200,
          f"{iid}/{pid}: release while pending={[h['_status'] for h in held]} clerk verify={own['_status']} "
          f"no callback={no_call['_status']} verify={ver['_status']}")
    stale = next(r for r in payment_rows(clerk) if r["payment"]["id"] == pid)["releaseBlockers"]
    re1 = clerk.post(f"/payments/{pid}/reprepare")
    re2 = clerk.post(f"/payments/{pid}/reprepare")
    who = release_by_anyone(pid)
    row = next(r for r in payment_rows(clerk) if r["payment"]["id"] == pid)
    check("After verification the payment is stale; the clerk re-prepares it onto the new account and it releases",
          any("re-prepare" in b for b in stale) and re1["_status"] == 200 and re2["_status"] == 409 and who and (row["bankAccount"] or "").endswith("3210"),
          f"blocker={[b for b in stale if 're-prepare' in b][:1]} re-prepare={re1['_status']} again={re2['_status']} released by {who} to {row['bankAccount']}")


def scenario_fail_retry(clerk):
    """12. Two batches at the same moment; then a failed payment -> retry -> paid exactly once."""
    iid = submit_with_total(clerk, "650.00")
    if not iid or approve_all(iid) != "APPROVED":
        check("Failed payment retry", False, f"no clean bill approved ({iid})")
        return
    marcus = as_user("U002")
    pid = clerk.post("/payments/prepare", {"invoiceIds": [iid]})[0]["id"]
    release_by_anyone(pid)
    codes = parallel(lambda: as_user("U003").post("/payments/batch"), lambda: as_user("U001").post("/payments/batch"))
    sent = audit(marcus, pid, "PAYMENT_SENT")
    check("Two people create an EFT batch at the same moment: the payment goes out in exactly one batch",
          sorted(codes)[0] == 200 and sorted(codes)[1] in (409, 422) and len(sent) == 1, f"{pid}: batch calls={codes}; PAYMENT_SENT rows={len(sent)}")
    fail = marcus.post(f"/payments/{pid}/fail", {"reason": "Account closed (edge-case script)"})
    settle_failed = marcus.post(f"/payments/{pid}/settle")
    after_fail = detail(clerk, iid)["invoice"]["status"]
    retry = [p for p in payments_for(clerk, iid) if p["id"] != pid]
    rid = retry[0]["id"] if retry else "none"
    who = release_by_anyone(rid) if retry else None
    marcus.post("/payments/batch")
    st1 = marcus.post(f"/payments/{rid}/settle")
    st2 = marcus.post(f"/payments/{rid}/settle")
    late_fail = marcus.post(f"/payments/{rid}/fail", {"reason": "late"})
    pays = payments_for(clerk, iid)
    settled = [p for p in pays if p["status"] == "SETTLED"]
    check("Failed payment: bill back to scheduled with one retry; retry released, sent, settled; bill PAID exactly once",
          fail["_status"] == 200 and settle_failed["_status"] == 409 and after_fail == "SCHEDULED" and len(retry) == 1 and who
          and st1["_status"] == 200 and st2["_status"] == 409 and late_fail["_status"] == 409 and len(settled) == 1
          and detail(clerk, iid)["invoice"]["status"] == "PAID",
          f"{iid}: fail={fail['_status']} settle failed={settle_failed['_status']} after fail={after_fail} retry={rid} released by {who} "
          f"settle={st1['_status']}/{st2['_status']} fail after paid={late_fail['_status']} payments={[(p['id'], p['status']) for p in pays]}")


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

    for scenario in (scenario_boundaries, scenario_usd, scenario_delegation, scenario_double_approve, scenario_policy_edit,
                     scenario_bank_change, scenario_fail_retry):
        scenario(clerk)

    v = marcus.get("/audit/verify")
    check("Audit chain still verifies", v["valid"], f"{v['events']} events")

    Client("U002").post("/admin/reset")
    fails = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(fails)}/{len(results)} checks passed (database reset afterwards; click Process inbox to reload the batch)")
    sys.exit(len(fails))


if __name__ == "__main__":
    main()
