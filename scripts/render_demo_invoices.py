#!/usr/bin/env python3
"""Render dataset_demo/incoming_invoices/ (54 PDFs + 2 PNG scans) from dataset_demo_spec/incoming_invoices.json.

Each document is written as HTML and printed with headless Edge or Chrome (no Python packages needed): PDFs via
--print-to-pdf, the two "scans" via --screenshot with a slight tilt, grey paper and grain. The layout follows the
dataset_given invoices: vendor block first, then the title and number/date/due/terms (and PO), "Bill to", the line
table, subtotal / tax / total, and a "Remit to" line in the "Inst. 003 Transit 12345 Acct 123456789" form.

Usage: python scripts/render_demo_invoices.py [--only substring]
"""
import argparse
import html
import json
import shutil
import subprocess
import sys
import tempfile
import time
import zlib
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "dataset_demo_spec" / "incoming_invoices.json"
OUT = ROOT / "dataset_demo" / "incoming_invoices"
# Chrome first: headless Edge on Windows returns before the file is written.
BROWSERS = [r"C:\Program Files\Google\Chrome\Application\chrome.exe",
            r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
            r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
            "/usr/bin/google-chrome", "/usr/bin/chromium", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"]

EN = dict(invoice="INVOICE", credit="CREDIT NOTE", no="Invoice #", cno="Credit note #", date="Date", due="Due date",
          terms="Terms", po="PO #", bill="Bill to", desc="Description", qty="Qty", unit="Unit price", amt="Amount",
          sub="Subtotal", total="Total", remit="Remit to", eft="EFT / bank transfer", card="Payment: charged to the card on file",
          thanks="Thank you for your business.", receipt="Due on receipt")
FR = dict(invoice="FACTURE", credit="NOTE DE CRÉDIT", no="Facture n°", cno="Note de crédit n°", date="Date",
          due="Échéance", terms="Conditions", po="Bon de commande", bill="Facturer à", desc="Description", qty="Qté",
          unit="Prix unitaire", amt="Montant", sub="Sous-total", total="Total", remit="Virement",
          eft="EFT / virement bancaire", card="Paiement : porté à la carte au dossier", thanks="Merci de votre confiance.",
          receipt="Payable sur réception")
TAX_EN = {"HST13": "HST (13%)", "GST5": "GST (5%)", "QST9975": "QST (9.975%)", "PST7_BC": "PST (7%)"}
TAX_FR = {"HST13": "TVH (13 %)", "GST5": "TPS (5 %)", "QST9975": "TVQ (9,975 %)", "PST7_BC": "TVP (7 %)"}

# A few house styles so the batch doesn't look like one template. Text and its order stay the same in each.
STYLES = [
    "",  # plain, like dataset_given
    """.hdr{border-bottom:3px solid var(--a);padding-bottom:14px} h2.title{color:var(--a)} th{background:#f1f3f5}""",
    """body{font-family:Georgia,'Times New Roman',serif} .hdr{background:var(--a);color:#fff;padding:18px 20px;margin:-10px -10px 0}
       .hdr .vendor .name{color:#fff} h2.title{color:#fff} .totals .grand{color:var(--a)}""",
    """body{font-family:'Segoe UI',Tahoma,sans-serif} table.items{border:1px solid #bbb} table.items td{border-bottom:1px solid #e3e3e3}
       th{background:var(--a);color:#fff} th,td{padding:5px 8px}""",
]
ACCENTS = ["#1f4e79", "#2e7d32", "#8e2430", "#5b3f8c", "#b35900", "#00695c", "#37474f"]


def money(x, lang, cur):
    x = Decimal(x)
    neg = "-" if x < 0 else ""
    whole, frac = f"{abs(x):,.2f}".split(".")
    if lang == "fr":
        return f"{neg}{whole.replace(',', ' ')},{frac} $"
    return f"{neg}${whole}.{frac}"


def qty(q, lang):
    return q.replace(".", ",") if lang == "fr" else q


def e(s):
    return html.escape(str(s))


def document(it, idx):
    lang = it["language"]
    L = FR if lang == "fr" else EN
    cur = it["currency"]
    v = it["vendor"]
    credit = it["document_type"] == "credit_note"
    street, _, cityline = v["address"].partition(", ")
    taxline = []
    if v.get("gst"):
        taxline.append(f"{'TPS/TVH' if lang == 'fr' else 'GST/HST'} {v['gst']}")
    if v.get("qst"):
        taxline.append(f"{'TVQ' if lang == 'fr' else 'QST'} {v['qst']}")
    if v.get("country") == "US":
        h = zlib.crc32(v["name"].encode())
        taxline = [f"EIN {h % 90 + 10}-{h % 9000000 + 1000000}"]
    terms = it["terms"]
    if "2/10" in terms:
        terms = "2/10 net 30"
    elif terms == "Due on receipt":
        terms = L["receipt"]
    meta = [(L["cno"] if credit else L["no"], it["invoice_number"]), (L["date"], it["invoice_date"]),
            (L["due"], it["due_date"]), (L["terms"], terms)]
    if it.get("po_number"):
        meta.append((L["po"], it["po_number"]))
    rows = "".join(
        f"<tr><td>{e(l['description'])}</td><td class=n>{e(qty(l['quantity'], lang))}</td>"
        f"<td class=n>{money(l['unit_price'], lang, cur)}</td><td class=n>{money(l['amount'], lang, cur)}</td></tr>"
        for l in it["lines"])
    taxes = "".join(f"<tr><td>{(TAX_FR if lang == 'fr' else TAX_EN)[t['code']]}</td><td class=n>{money(t['amount'], lang, cur)}</td></tr>"
                    for t in it["taxes"])
    remit = it.get("remit_to")
    if remit:
        pay = (f"<div class=sec>{L['remit']}</div><div>Inst. {remit['institution']}&nbsp;&nbsp; Transit {remit['transit']}"
               f"&nbsp;&nbsp; Acct {remit['account']}</div><div>{L['eft']} - {e(v['name'])}</div>")
        if v["name"] == "Sparkle Pro Janitorial":
            pay += "<div class=notice>Please note our NEW banking details. Update your records and remit to the account above.</div>"
    else:
        pay = f"<div class=sec>{'Paiement' if lang == 'fr' else 'Payment'}</div><div>{L['card']}</div>"
    if v.get("country") == "US":
        pay += "<div class=small>Amounts in US dollars. No Canadian sales tax charged.</div>"
    if not v.get("gst") and v.get("country") != "US":
        pay += "<div class=small>Not registered for GST/HST.</div>"
    note = f"<div class=note>{e(it['notes'].replace('Footer: ', '').strip(chr(39)))}</div>" if it.get("notes") else ""
    style = STYLES[idx % len(STYLES)]
    accent = ACCENTS[(idx * 3) % len(ACCENTS)]
    scan = it["format"].startswith("PNG")
    scan_css = """
      html{background:#d9d9d6} body{font-family:Arial,Helvetica,sans-serif !important;font-variant-numeric:lining-nums;width:880px;min-height:1150px;margin:20px auto;padding:52px 60px;background:#f4f4f0;
        transform:rotate(-0.55deg);filter:contrast(1.08) blur(0.35px) grayscale(1);box-shadow:0 0 6px #999}
      body::after{content:"";position:fixed;inset:0;pointer-events:none;opacity:.22;
        background-image:url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='220' height='220'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='.9' numOctaves='2'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>")}
    """ if scan else "@page{size:Letter;margin:0.6in}"
    return f"""<!doctype html><html lang="{lang}"><head><meta charset="utf-8"><title>{e(it['invoice_number'])}</title><style>
:root{{--a:{accent}}}
body{{font-family:Arial,Helvetica,sans-serif;font-size:12.5px;color:#111;margin:0}} *{{font-variant-numeric:lining-nums}}
.hdr{{display:flex;justify-content:space-between;align-items:flex-start}}
.vendor .name{{font-size:21px;font-weight:bold;margin-bottom:4px}}
h2.title{{font-size:30px;margin:0 0 10px;text-align:right;letter-spacing:.5px}}
.meta{{text-align:right;line-height:1.5}}
.sec{{font-weight:bold;margin-top:28px;margin-bottom:3px}}
table.items{{width:100%;border-collapse:collapse;margin-top:30px}}
th{{text-align:left;border-bottom:1.5px solid #111;padding:4px 6px}} th.n,td.n{{text-align:right;white-space:nowrap}}
td{{padding:4px 6px}}
table.totals{{margin-left:auto;margin-top:10px;border-top:1.5px solid #111;min-width:330px}}
table.totals td{{padding:3px 6px}} .grand td{{font-weight:bold;font-size:14px}}
.notice{{margin-top:8px;padding:6px 8px;border:1.5px solid #b00;color:#b00;font-weight:bold;display:inline-block}}
.small,.note{{color:#555;margin-top:6px}} .foot{{margin-top:60px;color:#777;font-size:10.5px}}
{style}{scan_css}
</style></head><body>
<div class=hdr><div class=vendor><div class=name>{e(v['name'])}</div><div>{e(street)}</div><div>{e(cityline)}</div>
<div>{'&nbsp;&nbsp;'.join(e(t) for t in taxline)}</div><div>{e(v['email'])}</div></div>
<div><h2 class=title>{L['credit'] if credit else L['invoice']}</h2><div class=meta>{''.join(f'<div>{k}: {e(val)}</div>' for k, val in meta)}</div></div></div>
<div class=sec>{L['bill']}</div><div>{e(it['bill_to']['name'])}</div><div>{e(it['bill_to']['address'])}</div>
<div>GST/HST {e(it['bill_to']['gst'])}</div>
<table class=items><thead><tr><th>{L['desc']}</th><th class=n>{L['qty']}</th><th class=n>{L['unit']}</th><th class=n>{L['amt']}</th></tr></thead>
<tbody>{rows}</tbody></table>
<table class=totals><tr><td>{L['sub']}</td><td class=n>{money(it['subtotal'], lang, cur)}</td></tr>{taxes}
<tr class=grand><td>{L['total']} {cur}</td><td class=n>{money(it['total'], lang, cur)}</td></tr></table>
{pay}{note}<div class=foot>{L['thanks']}</div></body></html>"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    browser = next((b for b in BROWSERS if Path(b).exists()), None) or shutil.which("msedge") or shutil.which("chrome")
    if not browser:
        sys.exit("Edge or Chrome is needed to render the documents")
    items = json.loads(SPEC.read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        profile = tmp / "profile"
        for idx, it in enumerate(items):
            if args.only not in it["file"]:
                continue
            page = tmp / f"doc{idx}.html"
            page.write_text(document(it, idx), encoding="utf-8")
            target = OUT / it["file"]
            base = [browser, "--headless=new", "--disable-gpu", "--no-first-run", "--hide-scrollbars",
                    f"--user-data-dir={profile}"]
            if it["format"].startswith("PNG"):
                cmd = base + ["--window-size=1044,1340", f"--screenshot={target}", page.as_uri()]
            else:
                cmd = base + ["--no-pdf-header-footer", f"--print-to-pdf={target}", page.as_uri()]
            subprocess.run(cmd, check=True, capture_output=True, timeout=120)
            for _ in range(50):  # Edge may still be writing
                if target.exists() and target.stat().st_size > 0:
                    break
                time.sleep(0.2)
            if not target.exists() or target.stat().st_size == 0:
                sys.exit(f"render failed: {it['file']}")
            print(f"{target.stat().st_size:>8}  {it['file']}")


if __name__ == "__main__":
    main()
