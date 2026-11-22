#!/usr/bin/env python3
"""Print every non-historical invoice with its open flags (as the AP clerk). Useful after "Process inbox".

Usage: python scripts/flag_report.py [--api http://localhost:8088] [--all-statuses] [--json out.json]
"""
import argparse
import http.cookiejar
import json
import urllib.request
from collections import Counter


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8088")
    ap.add_argument("--json", default=None)
    args = ap.parse_args()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    op.open(urllib.request.Request(f"{args.api}/api/auth/login", data=b'{"userId":"U004"}',
                                   headers={"Content-Type": "application/json"}, method="POST")).read()
    invoices = json.loads(op.open(f"{args.api}/api/invoices").read())
    out, codes = [], Counter()
    for x in sorted(invoices, key=lambda x: x["id"]):
        det = json.loads(op.open(f"{args.api}/api/invoices/{x['id']}").read())
        flags = [f for f in det["flags"] if f["status"] == "OPEN"]
        codes.update(f["ruleCode"] for f in flags)
        print(f"{x['id']} {x['vendorName']} #{x['vendorInvoiceNumber']} {x['status']} dept={x['departmentId']} total={x['totalCad']}")
        for f in flags:
            print(f"   [{f['severity']}] {f['ruleCode']}: {f['message']}")
        out.append({"invoice": x, "flags": flags})
    print("\nStatuses:", dict(Counter(x["status"] for x in invoices)))
    print("Open flags by rule:", dict(sorted(codes.items())))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(out, fh, indent=1)


if __name__ == "__main__":
    main()
