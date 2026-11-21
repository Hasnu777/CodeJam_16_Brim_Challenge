#!/usr/bin/env python3
"""Run every file in dataset_given/incoming_invoices through the app's extractor and report field accuracy.

Calls POST /api/extraction/eval (reads the file, does not create a bill) as the AP clerk, compares the app's
normalized output with scripts/ground_truth.json, and prints per-field and overall accuracy plus every miss.

Usage: python scripts/eval_extraction.py [--api http://localhost:8088] [--only substring] [--json out.json]
"""
import argparse
import http.cookiejar
import json
import sys
import time
import urllib.parse
import urllib.request
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIELDS = ["vendorId", "invoiceNumber", "invoiceDate", "dueDate", "currency", "subtotal", "total", "taxes",
          "lineCount", "gstNumber", "remitAccount", "shipToProvince", "documentType"]


def norm(field, v):
    if v in (None, "", "None", "null"):
        return None
    if field in ("subtotal", "total"):
        return str(Decimal(str(v)).quantize(Decimal("0.01")))
    if field == "taxes":
        return {t["code"]: str(Decimal(t["amount"]).quantize(Decimal("0.01"))) for t in v} if isinstance(v, list) else \
            {k: str(Decimal(x).quantize(Decimal("0.01"))) for k, x in v.items()}
    if field == "lineCount":
        return int(v)
    if field == "invoiceNumber":
        return str(v).strip().upper()
    if field == "documentType":
        return "credit_note" if "credit" in str(v).lower() else "invoice"
    return str(v).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8088")
    ap.add_argument("--only", default=None)
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    truth = json.loads((ROOT / "scripts" / "ground_truth.json").read_text(encoding="utf-8"))
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    login = urllib.request.Request(f"{args.api}/api/auth/login", data=json.dumps({"userId": "U004"}).encode(),
                                   headers={"Content-Type": "application/json"}, method="POST")
    opener.open(login).read()

    per_field = {f: [0, 0] for f in FIELDS}
    misses, methods, results = [], {}, {}
    t0 = time.time()
    for name, gt in truth.items():
        if args.only and args.only not in name:
            continue
        req = urllib.request.Request(f"{args.api}/api/extraction/eval?file={urllib.parse.quote(name)}", method="POST")
        try:
            res = json.loads(opener.open(req, timeout=600).read())
        except Exception as e:  # noqa: BLE001 - report and keep going
            res = {"error": str(e), "method": "ERROR"}
        methods[res.get("method")] = methods.get(res.get("method"), 0) + 1
        got = res.get("normalized") or {}
        results[name] = {"method": res.get("method"), "error": res.get("error"), "normalized": got}
        for f in FIELDS:
            want, have = norm(f, gt.get(f)), norm(f, got.get(f))
            per_field[f][1] += 1
            if want == have:
                per_field[f][0] += 1
            else:
                misses.append((name, f, want, have))
        print(f"  {name:42} {res.get('method', '?'):14} {sum(1 for m in misses if m[0] == name)} misses", flush=True)

    total_ok = sum(v[0] for v in per_field.values())
    total_n = sum(v[1] for v in per_field.values())
    print(f"\nFiles: {len(results)}   methods: {methods}   time: {time.time() - t0:.0f}s")
    print(f"{'field':16} accuracy")
    for f, (ok, n) in per_field.items():
        print(f"{f:16} {ok:3}/{n:<3} {100 * ok / n:5.1f}%")
    print(f"{'OVERALL':16} {total_ok}/{total_n} {100 * total_ok / total_n:5.1f}%")
    if misses:
        print("\nMisses (file, field, expected, got):")
        for m in misses:
            print("  ", *m)
    if args.json:
        Path(args.json).write_text(json.dumps({"perField": per_field, "overall": [total_ok, total_n], "misses": misses,
                                               "methods": methods, "results": results}, indent=1), encoding="utf-8")
    sys.exit(0)


if __name__ == "__main__":
    main()
