#!/usr/bin/env python3
"""Build scripts/ground_truth.json for the extraction eval.

PDFs: parsed from `pdftotext -raw -enc UTF-8` with this script's own regexes (an implementation independent of
the Java extractor). The two PNG scans have no text layer, so their values are hand-transcribed from the images.
Every value is the normalized form the app should produce: ISO dates, plain decimal strings, dataset tax codes.

Usage: python scripts/build_ground_truth.py [dataset_dir]   (needs pdftotext on PATH)
"""
import csv
import json
import re
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "dataset_given"
OUT = ROOT / "scripts" / "ground_truth.json"

HARBORLINE_GST = "100000001RT0001"
MONEY = r"\(?-?\$?-?\s?\d{1,3}(?:[ ,  ]\d{3})*(?:[.,]\d{2})\)?\s?\$?"


def money(s):
    if s is None:
        return None
    neg = "-" in s or "(" in s
    n = re.sub(r"[^0-9.,]", "", s)
    if re.search(r",\d{2}$", n):  # French decimal comma
        n = n[:-3].replace(",", "").replace(".", "") + "." + n[-2:]
    else:
        n = n.replace(",", "")
    v = Decimal(n).quantize(Decimal("0.01"))
    return str(-v if neg else v)


def label(lines, *labels):
    for lab in labels:
        for line in lines:
            m = re.match(r"^" + re.escape(lab) + r"\s*:\s*(.*)$", line, re.I)
            if m:
                return m.group(1).strip()
    return None


def tax_code(lbl):
    u = lbl.upper()
    if "HST" in u or "TVH" in u:
        return "HST13"
    if "QST" in u or "TVQ" in u:
        return "QST9975"
    if "PST" in u:
        return "PST7_BC"
    return "GST5"


def province(bill_to):
    for city, prov in (("Toronto", "ON"), ("Vancouver", "BC"), ("Calgary", "AB"), ("Montreal", "QC")):
        if city in bill_to:
            return prov
    return None


def parse(text):
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    gst = next((g for g in (a + "RT" + b for a, b in re.findall(r"(\d{9})RT(\d{4})", text)) if g != HARBORLINE_GST), None)
    inv_no = label(lines, "Invoice Number", "Invoice #", "Invoice No.", "INV NO", "Facture n°")
    date = label(lines, "Issued", "Invoice date", "DATE", "Date")
    due = label(lines, "Due by", "Due date", "Payment due", "DUE", "Échéance")
    po = label(lines, "PO REF", "Your PO", "PO #", "Customer PO") or None
    bill_idx = next(i for i, ln in enumerate(lines) if ln.lower() in ("bill to", "facturer à"))
    bill_to = lines[bill_idx + 1] + " " + lines[bill_idx + 2]
    subtotal = total = None
    taxes = {}
    n_lines = 0
    in_items = False
    for ln in lines:
        u = ln.upper()
        if u.startswith("DESCRIPTION"):
            in_items = True
            continue
        if u.startswith("SUBTOTAL") or u.startswith("SOUS-TOTAL"):
            in_items = False
            subtotal = money(re.search(MONEY + r"\s*$", ln).group(0))
            continue
        if u.startswith("TOTAL"):
            total = money(re.search(MONEY + r"\s*$", ln).group(0))
            continue
        m = re.match(r"^((?:GST|HST|QST|PST|TPS|TVQ|TVH)[^\d(]*)\(\s*[\d.,]+\s*%\s*\)\s+(" + MONEY + r")\s*$", ln)
        if m and not in_items:
            code = tax_code(m.group(1))
            taxes[code] = money(m.group(2))
            continue
        if in_items and re.search(MONEY + r"\s+" + MONEY + r"\s*$", ln):
            n_lines += 1
    remit = re.search(r"Acct\s+(\d+)", text)
    return {
        "vendorName": lines[0],
        "gstNumber": gst,
        "invoiceNumber": inv_no,
        "invoiceDate": date,
        "dueDate": due,
        "currency": "USD" if "TOTAL USD" in text.upper() else "CAD",
        "subtotal": subtotal,
        "total": total,
        "taxes": taxes,
        "lineCount": n_lines,
        "remitAccount": remit.group(1) if remit else None,
        "shipToProvince": province(bill_to),
        "poNumber": po,
        "documentType": "credit_note" if "CREDIT NOTE" in text.upper() else "invoice",
    }


# Hand-transcribed from the two scanned images (no text layer).
SCANS = {
    "scan_8049.png": {
        "vendorName": "Cascadia Cartage Ltd.", "gstNumber": "669086667RT0001", "invoiceNumber": "1624",
        "invoiceDate": "2026-10-24", "dueDate": "2026-11-23", "currency": "CAD", "subtotal": "5821.87",
        "total": "6693.69", "taxes": {"GST5": "291.09", "QST9975": "580.73"}, "lineCount": 5,
        "remitAccount": "953389679", "shipToProvince": "QC", "poNumber": None, "documentType": "invoice",
    },
    "scan_8749.png": {
        "vendorName": "Prairie Natural Gas Utility", "gstNumber": "862863208RT0001", "invoiceNumber": "PR26-1012",
        "invoiceDate": "2026-10-22", "dueDate": "2026-11-12", "currency": "CAD", "subtotal": "4494.55",
        "total": "4719.28", "taxes": {"GST5": "224.73"}, "lineCount": 1,
        "remitAccount": "704593075", "shipToProvince": "AB", "poNumber": None, "documentType": "invoice",
    },
}


def main():
    vendors = list(csv.DictReader(open(DATA / "data" / "vendors.csv", encoding="utf-8")))
    by_gst = {v["gst_hst_number"]: v["vendor_id"] for v in vendors if v["gst_hst_number"]}
    by_name = {v["legal_name"]: v["vendor_id"] for v in vendors}
    truth = {}
    for f in sorted((DATA / "incoming_invoices").iterdir()):
        if f.suffix.lower() == ".pdf":
            text = subprocess.run(["pdftotext", "-raw", "-enc", "UTF-8", str(f), "-"], capture_output=True, check=True).stdout.decode("utf-8")
            t = parse(text)
        elif f.name in SCANS:
            t = dict(SCANS[f.name])
        else:
            continue
        t["vendorId"] = by_gst.get(t["gstNumber"]) or by_name.get(t["vendorName"])
        truth[f.name] = t
    OUT.write_text(json.dumps(truth, indent=1, ensure_ascii=False), encoding="utf-8")
    missing = {k: [f for f, v in t.items() if v in (None, "") and f not in ("poNumber", "vendorId")] for k, t in truth.items()}
    print(f"{len(truth)} files -> {OUT}")
    for k, m in missing.items():
        if m:
            print(f"  {k}: no {', '.join(m)}")
    for k, t in truth.items():
        lines_ok = t["subtotal"] is not None and t["total"] is not None and \
            Decimal(t["subtotal"]) + sum(Decimal(v) for v in t["taxes"].values()) == Decimal(t["total"])
        if not lines_ok:
            print(f"  {k}: subtotal + taxes != total ({t['subtotal']} + {t['taxes']} vs {t['total']})")


if __name__ == "__main__":
    main()
