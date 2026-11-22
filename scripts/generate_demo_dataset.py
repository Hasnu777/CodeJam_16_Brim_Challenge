#!/usr/bin/env python3
"""Generate dataset_demo/: a second synthetic Harborline Logistics AP dataset in exactly the format of dataset_given/.

What stays the same as dataset_given (the app hardcodes these): the company, users.csv, departments.csv,
gl_accounts.csv, tax_codes.csv, approval_policies.csv, the as-of date (2026-11-02) and every README row count
(so the seeder's count check passes and the AP-00427 / PAY-00416 / V057 / BC-010 sequences still line up).

What is new: the vendor master, contracts, POs, FX rates, bank-change history, Jan-Oct history (invoices, lines,
approvals, payments, bank statement, card transactions) and the inbox (emails.json), each with its own planted mess.

incoming_invoices/ is left empty: the PDFs are made separately. dataset_demo_spec/ describes every file the
emails reference (full invoice content, the planted issue, and a ground_truth.json in the format of
scripts/ground_truth.json) so the PDFs can be built to match.

Usage: python scripts/generate_demo_dataset.py   (deterministic; rerunning gives identical output)
"""
import csv
import datetime as dt
import json
import random
import shutil
from collections import Counter, defaultdict
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "dataset_given"
OUT = ROOT / "dataset_demo"
SPEC = ROOT / "dataset_demo_spec"
R = random.Random(20261122)
AS_OF = dt.date(2026, 11, 2)
D = dt.date
CENT = Decimal("0.01")

# Exact row counts of dataset_given/README.md
TARGET = dict(invoices=426, invoice_lines=809, approvals=474, payments=415, bank_statement=354,
              card_transactions=590, contracts=27, purchase_orders=52, vendors=56, bank_change_history=9)


def q(x):
    return Decimal(x).quantize(CENT, ROUND_HALF_UP)


def m2(x):
    return f"{q(x):.2f}"


def rdate(a, b):
    return a + dt.timedelta(days=R.randint(0, (b - a).days))


def add(d, n):
    return d + dt.timedelta(days=n)


MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
          "November", "December"]
MOIS = ["janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre",
        "decembre"]

# ----------------------------------------------------------------------------------------------------------------
# Reference data copied from dataset_given
# ----------------------------------------------------------------------------------------------------------------


def read(name):
    with open(SRC / "data" / f"{name}.csv", encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


USERS = {u["user_id"]: u for u in read("users")}
DEPTS = {d["department_id"]: d for d in read("departments")}
OFFICES = {
    "ON": ("Harborline Logistics - Toronto HQ", "410 Queens Quay W, Suite 900, Toronto ON M5V 2Z3"),
    "BC": ("Harborline Logistics - Vancouver Office", "1188 W Pender St, Suite 600, Vancouver BC V6E 0A2"),
    "AB": ("Harborline Logistics - Calgary Warehouse", "7220 54 St SE, Calgary AB T2C 4X3"),
    "QC": ("Harborline Logistique - Bureau de Montreal", "2020 rue University, bureau 1100, Montreal QC H3A 2A5"),
}
HARBORLINE_GST = "100000001RT0001"

# ----------------------------------------------------------------------------------------------------------------
# FX: a fresh random walk over the same 306 days
# ----------------------------------------------------------------------------------------------------------------
FX = {}
rate = 1.3622
d0 = D(2026, 1, 1)
while d0 <= AS_OF:
    rate += R.gauss(-0.0002, 0.0026)
    rate = min(max(rate, 1.3150), 1.3990)
    FX[d0] = Decimal(f"{rate:.4f}")
    d0 = add(d0, 1)

# ----------------------------------------------------------------------------------------------------------------
# Vendor master
# ----------------------------------------------------------------------------------------------------------------
# id, legal name, category, GL, street, city, prov/state, postal, country, terms, discount, method, card, po, status,
# onboarded, number style, number prefix, email domain, line kind
V_ROWS = [
    ("V001", "Northwind Freight Lines Ltd.", "Freight & Carriers", "5010", "2280 Burlington St E", "Hamilton", "ON", "L8H 3L2", "CA", 30, "", "EFT", "no", "no", "active", "2021-03-15", "Z6", "", "northwindfreight", "freight"),
    ("V002", "Bayshore Transport Inc.", "Freight & Carriers", "5010", "455 Courtneypark Dr E", "Mississauga", "ON", "L5T 2S5", "CA", 30, "", "EFT", "no", "no", "active", "2020-06-02", "PFX", "BA", "bayshoretransport", "freight"),
    ("V003", "Red River Haulage", "Freight & Carriers", "5010", "1650 Inkster Blvd", "Winnipeg", "MB", "R2X 2W8", "CA", 30, "", "CHEQUE", "no", "no", "active", "2019-11-20", "FA", "", "redriverhaulage", "freight"),
    ("V004", "Coastline Cartage Ltd.", "Freight & Carriers", "5010", "12120 Horseshoe Way", "Richmond", "BC", "V7A 4V5", "CA", 30, "", "EFT", "no", "no", "active", "2021-05-10", "N", "", "coastlinecartage", "freight"),
    ("V005", "Fleuve Logistique Inc.", "Freight & Carriers", "5010", "8400 boul. Henri-Bourassa O", "Montreal", "QC", "H4S 1P4", "CA", 30, "", "EFT", "no", "no", "active", "2020-09-14", "FA", "", "fleuvelogistique", "freight"),
    ("V006", "Foothills Courier Co.", "Freight & Carriers", "5010", "3915 61 Ave SE", "Calgary", "AB", "T2C 1V5", "CA", 30, "", "EFT", "no", "no", "active", "2024-08-19", "Z6", "", "foothillscourier", "freight"),
    ("V007", "Ridgeline Fuel Services", "Fuel & Fleet", "5020", "88 Industry St", "Toronto", "ON", "M6M 4L8", "CA", 15, "", "EFT", "yes", "no", "active", "2022-02-08", "PFX", "RI", "ridgelinefuel", "fuelcard"),
    ("V008", "Bluewater Fleet Cards Inc.", "Fuel & Fleet", "5020", "10120 178 St NW", "Edmonton", "AB", "T5S 1R1", "CA", 15, "", "EFT", "no", "no", "active", "2025-11-03", "FA", "", "bluewaterfleet", "fuelcard"),
    ("V009", "Granite Tire & Fleet Service", "Fuel & Fleet", "5020", "1205 Barton St E", "Hamilton", "ON", "L8H 2V5", "CA", 15, "", "EFT", "no", "no", "active", "2023-04-11", "N", "", "granitetire", "tires"),
    ("V010", "Oakridge Pallet Co.", "Warehouse & Packaging", "5040", "61 Brockley Dr", "Hamilton", "ON", "L8E 3C3", "CA", 30, "2/10 net 30", "EFT", "no", "yes", "active", "2020-10-01", "Z6", "", "oakridgepallet", "warehouse"),
    ("V011", "Crestline Packaging Supply", "Warehouse & Packaging", "5040", "8160 North Fraser Way", "Burnaby", "BC", "V5J 5M8", "CA", 30, "", "EFT", "no", "yes", "active", "2019-04-23", "Z6", "", "crestlinepackaging", "warehouse"),
    ("V012", "Anchor Racking Systems", "Warehouse & Packaging", "5040", "3502 Millar Ave", "Saskatoon", "SK", "S7P 0B1", "CA", 30, "", "CHEQUE", "no", "yes", "active", "2019-07-30", "PFX", "AN", "anchorracking", "warehouse"),
    ("V013", "Sentinel Safety Supply", "Safety Supplies", "5050", "2711 Hopewell Pl NE", "Calgary", "AB", "T1Y 7J7", "CA", 30, "", "EFT", "no", "yes", "active", "2019-09-05", "N", "", "sentinelsafety", "safety"),
    ("V014", "Steeltoe Workwear Ltd.", "Safety Supplies", "5050", "6880 Columbus Rd", "Mississauga", "ON", "L5T 2G1", "CA", 30, "", "EFT", "no", "yes", "active", "2025-02-17", "N", "", "steeltoeworkwear", "safety"),
    ("V015", "Driftwood Software Inc.", "Software & SaaS", "6110", "155 Wellington St W", "Toronto", "ON", "M5V 3H1", "CA", 30, "", "CARD", "yes", "no", "active", "2024-03-12", "INV", "", "driftwoodsoftware", "fixed"),
    ("V016", "Corvid Analytics Inc.", "Software & SaaS", "6110", "1201 Western Ave", "Seattle", "WA", "98101", "US", 30, "", "CARD", "yes", "no", "active", "2023-07-18", "Z6", "", "corvidanalytics", "fixed"),
    ("V017", "Fernleaf Collaboration Ltd.", "Software & SaaS", "6110", "980 Howe St", "Vancouver", "BC", "V6Z 0C8", "CA", 30, "", "CARD", "yes", "no", "active", "2024-05-27", "PFX", "FE", "fernleaf", "fixed"),
    ("V018", "Hightide CRM Inc.", "Software & SaaS", "6110", "600 Congress Ave", "Austin", "TX", "78701", "US", 30, "", "CARD", "yes", "no", "active", "2023-11-02", "FA", "", "hightidecrm", "fixed"),
    ("V019", "Abacus Ledger Systems", "Software & SaaS", "6110", "5770 Hurontario St", "Mississauga", "ON", "L5R 3G5", "CA", 30, "", "EFT", "no", "no", "active", "2022-01-24", "N", "", "abacusledger", "fixed"),
    ("V020", "Signet Documents Ltee", "Software & SaaS", "6110", "1100 rue Peel", "Montreal", "QC", "H3B 4P5", "CA", 30, "", "EFT", "no", "no", "active", "2021-02-09", "FA", "", "signetdocuments", "fixed"),
    ("V021", "Stratus Peak Cloud LLC", "Cloud Hosting", "6130", "233 S Wacker Dr", "Chicago", "IL", "60606", "US", 0, "", "CARD", "yes", "no", "active", "2019-05-06", "Z6", "", "stratuspeak", "cloud"),
    ("V022", "Beacon Mobile Business", "Telecom", "6120", "25 York St", "Toronto", "ON", "M5J 2V5", "CA", 21, "", "EFT", "no", "no", "active", "2024-10-08", "PFX", "BE", "beaconmobile", "fixed"),
    ("V023", "Lumiere Fibre Communications", "Telecom", "6120", "7250 rue du Mile End", "Montreal", "QC", "H2R 2W1", "CA", 21, "", "EFT", "no", "no", "active", "2025-03-03", "INV", "", "lumierefibre", "fixed"),
    ("V024", "Inkwell Office Supply", "Office Supplies", "6210", "1450 Castlefield Ave", "Toronto", "ON", "M6B 4L9", "CA", 30, "2/10 net 30", "EFT", "yes", "no", "active", "2023-06-21", "INV", "", "inkwelloffice", "office"),
    ("V025", "Workform Furniture Ltd.", "Office Furniture", "6220", "1680 Cliveden Ave", "Delta", "BC", "V3M 6V5", "CA", 30, "", "EFT", "no", "yes", "active", "2023-09-11", "N", "", "workformfurniture", "furniture"),
    ("V026", "Ashby & Crane LLP", "Legal", "6310", "66 Wellington St W", "Toronto", "ON", "M5K 1E6", "CA", 30, "", "EFT", "no", "no", "active", "2023-04-03", "Z6", "", "ashbycrane", "legal"),
    ("V027", "Desjardins Lavallee S.E.N.C.R.L.", "Legal", "6310", "1501 avenue McGill College", "Montreal", "QC", "H3A 3N9", "CA", 30, "", "EFT", "no", "no", "active", "2022-05-16", "FA", "", "desjardinslavallee", "legal"),
    ("V028", "Tallyhouse Chartered Professional Accountants", "Accounting & Audit", "6320", "4100 Yonge St", "Toronto", "ON", "M2P 2B5", "CA", 30, "", "EFT", "no", "no", "active", "2019-10-28", "N", "", "tallyhousecpa", "accounting"),
    ("V029", "Meridian Advisory Group", "Consulting", "6330", "21 King St W", "Hamilton", "ON", "L8P 4W7", "CA", 30, "", "EFT", "no", "no", "active", "2024-02-20", "N", "", "meridianadvisory", "consulting"),
    ("V030", "Westbrook Consulting Partners", "Consulting", "6330", "10180 101 St NW", "Edmonton", "AB", "T5J 3S4", "CA", 30, "", "EFT", "no", "no", "active", "2019-03-04", "PFX", "WE", "westbrookconsulting", "consulting"),
    ("V031", "Kite & Compass Creative", "Marketing & Advertising", "6410", "55 Water St", "Vancouver", "BC", "V6B 1A1", "CA", 30, "", "EFT", "no", "no", "active", "2023-01-30", "N", "", "kitecompass", "marketing"),
    ("V032", "Brightwave Media Buying", "Marketing & Advertising", "6410", "1 Hunter St E", "Hamilton", "ON", "L8N 3W1", "CA", 30, "", "EFT", "no", "no", "active", "2019-08-12", "PFX", "BR", "brightwavemedia", "marketing"),
    ("V033", "Pressworks Print & Signage", "Printing & Signage", "6420", "2600 Argentia Rd", "Mississauga", "ON", "L5N 5V4", "CA", 30, "2/10 net 30", "EFT", "no", "no", "active", "2023-05-15", "N", "", "pressworks", "printing"),
    ("V034", "Shieldstone Insurance Brokers", "Insurance", "6510", "200 Front St W", "Toronto", "ON", "M5V 3K2", "CA", 0, "", "EFT", "no", "no", "active", "2022-03-01", "PFX", "SH", "shieldstone", "fixed"),
    ("V035", "Roadguard Commercial Insurance", "Insurance", "6510", "10303 Jasper Ave", "Edmonton", "AB", "T5J 3N6", "CA", 0, "", "EFT", "no", "no", "active", "2019-01-21", "INV", "", "roadguard", "fixed"),
    ("V036", "Front Street Properties Inc.", "Rent & Property", "6610", "181 Bay St", "Toronto", "ON", "M5J 2T3", "CA", 0, "", "EFT", "no", "no", "active", "2022-09-26", "Z6", "", "frontstreetproperties", "rent"),
    ("V037", "Burrard Commercial Realty", "Rent & Property", "6610", "1075 W Georgia St", "Vancouver", "BC", "V6E 3C9", "CA", 0, "", "EFT", "no", "no", "active", "2021-11-08", "PFX", "BU", "burrardrealty", "rent"),
    ("V038", "Chinook Industrial Parks", "Rent & Property", "6610", "5940 Macleod Trail SW", "Calgary", "AB", "T2H 2G4", "CA", 0, "", "EFT", "no", "no", "active", "2019-06-17", "FA", "", "chinookparks", "rent"),
    ("V039", "Immeubles Saint-Laurent", "Rent & Property", "6610", "1250 boul. Rene-Levesque O", "Montreal", "QC", "H3B 4W8", "CA", 0, "", "CHEQUE", "no", "no", "active", "2020-11-30", "FA", "", "immeublessaintlaurent", "rent"),
    ("V040", "Ontario Lakeside Hydro", "Utilities", "6620", "14 Carlton St", "Toronto", "ON", "M5B 1K5", "CA", 21, "", "EFT", "no", "no", "active", "2025-09-22", "INV", "", "lakesidehydro", "utility"),
    ("V041", "Coastal Energy Utility", "Utilities", "6620", "333 Dunsmuir St", "Vancouver", "BC", "V6B 5R3", "CA", 21, "", "EFT", "no", "no", "active", "2019-08-26", "Z6", "", "coastalenergy", "utility"),
    ("V042", "Prairie Gas & Power", "Utilities", "6620", "110 12 Ave SW", "Calgary", "AB", "T2R 0G7", "CA", 21, "", "EFT", "no", "no", "active", "2024-07-15", "PFX", "PR", "prairiegaspower", "utility"),
    ("V043", "Handyline Facility Services", "Repairs & Maintenance", "6630", "40 Bertrand Ave", "Toronto", "ON", "M1L 2P6", "CA", 30, "", "EFT", "no", "yes", "active", "2022-04-04", "FA", "", "handylinefacility", "repairs"),
    ("V044", "Gearwork Industrial Maintenance", "Repairs & Maintenance", "6630", "4404 75 Ave SE", "Calgary", "AB", "T2C 2K9", "CA", 30, "", "EFT", "no", "yes", "active", "2020-06-29", "N", "", "gearworkindustrial", "repairs"),
    ("V045", "Sparkle Pro Janitorial", "Janitorial", "6640", "3200 Dufferin St", "Toronto", "ON", "M6A 2T3", "CA", 30, "", "EFT", "no", "no", "active", "2023-03-06", "INV", "", "sparklepro", "fixed"),
    ("V046", "Waypoint Corporate Travel", "Travel", "6710", "100 King St W", "Toronto", "ON", "M5X 1C9", "CA", 15, "", "CARD", "yes", "no", "active", "2024-11-25", "Z6", "", "waypointtravel", "travel"),
    ("V047", "Cedar & Sage Events", "Events & Catering", "6720", "1500 Main St", "Vancouver", "BC", "V6A 2W5", "CA", 30, "", "EFT", "yes", "yes", "active", "2021-10-12", "PFX", "CE", "cedarsage", "events"),
    ("V048", "Talentpath Recruiting", "Recruiting", "6810", "120 King St W", "Hamilton", "ON", "L8P 4V2", "CA", 30, "", "EFT", "no", "no", "active", "2019-09-09", "FA", "", "talentpath", "recruiting"),
    ("V049", "Wellcrest Benefits Administration", "Benefits Admin", "6820", "5935 Airport Rd", "Mississauga", "ON", "L4V 1W5", "CA", 15, "", "EFT", "no", "no", "active", "2019-10-07", "N", "", "wellcrestbenefits", "fixed"),
    ("V050", "Compass Compliance Training Inc.", "Training & Compliance", "6910", "1801 California St", "Denver", "CO", "80202", "US", 30, "", "CARD", "yes", "no", "active", "2020-03-23", "N", "", "compasscompliance", "training"),
    # --- master-data mess ---
    # Duplicate record of V001: same address and bank account, different GST number.
    ("V051", "Northwind Freight Lines Limited", "Freight & Carriers", "5010", "2280 Burlington St E", "Hamilton", "ON", "L8H 3L2", "CA", 30, "", "EFT", "no", "no", "active", "2024-10-21", "Z6", "", "northwindfreight", "freight"),
    # Lookalike of V026 Ashby & Crane LLP: different GST, bank and email domain.
    ("V052", "Ashby and Crane LLP", "Legal", "6310", "390 Bay St", "Toronto", "ON", "M5H 2Y2", "CA", 30, "", "EFT", "no", "no", "active", "2021-01-11", "PFX", "AC", "ashbyandcrane-llp", "legal"),
    # Duplicate record of V002, on hold, same bank account.
    ("V053", "Bayshore Transport Incorporated", "Freight & Carriers", "5010", "7035 Ordan Dr", "Mississauga", "ON", "L5T 1T1", "CA", 30, "", "EFT", "no", "no", "on_hold", "2022-01-17", "PFX", "BA", "bayshoretransport", "freight"),
    ("V054", "Maplewood Catering Co.", "Events & Catering", "6720", "2150 Granville St", "Vancouver", "BC", "V6H 3E9", "CA", 30, "", "EFT", "no", "yes", "pending_review", "2019-05-13", "N", "", "maplewoodcatering", "events"),
    ("V055", "Trillium Safety Consulting", "Consulting", "6330", "33 City Centre Dr", "Mississauga", "ON", "L5B 2N5", "CA", 30, "", "EFT", "no", "no", "pending_review", "2021-12-06", "N", "", "trilliumsafety", "consulting"),
    ("V056", "Copperline Print Brokers", "Printing & Signage", "6420", "75 James St N", "Hamilton", "ON", "L8R 2K5", "CA", 30, "", "EFT", "no", "no", "pending_review", "2020-02-24", "N", "", "copperlineprint", "printing"),
]
INSTITUTIONS = ["001", "002", "003", "004", "006", "010", "016"]
VENDORS = {}
for row in V_ROWS:
    (vid, name, cat, gl, street, city, prov, postal, country, terms, disc, method, card, po, status, onb,
     style, pfx, domain, kind) = row
    v = dict(vendor_id=vid, legal_name=name, category=cat, default_gl_account=gl, street=street, city=city,
             province_or_state=prov, postal=postal, country=country, payment_terms_days=terms,
             early_pay_discount=disc, preferred_payment_method=method, accepts_card_payment=card, requires_po=po,
             status=status, onboarded_at=onb, style=style, pfx=pfx, domain=domain, kind=kind,
             counter=R.randint(200, 2900) if style in ("N", "PFX") else R.randint(300, 1500))
    v["address"] = f"{street}, {city} {prov} {postal}"
    v["gst_hst_number"] = "" if country == "US" or vid == "V054" else f"{R.randint(100000000, 999999999)}RT0001"
    v["qst_number"] = f"{R.randint(1000000000, 9999999999)}TQ0001" if prov == "QC" else ""
    if method == "CARD":
        v.update(bank_institution="", bank_transit="", bank_account="")
    else:
        v.update(bank_institution=R.choice(INSTITUTIONS), bank_transit=f"{R.randint(0, 99999):05d}",
                 bank_account=str(R.randint(10_000_000, 999_999_999)))
    v["ap_contact_email"] = f"ar@{domain}.example"
    v["bank_details_updated_at"] = ""
    VENDORS[vid] = v
# Duplicate records share the original's bank account; V051 also shares the address.
for dup, orig in (("V051", "V001"), ("V053", "V002")):
    for k in ("bank_institution", "bank_transit", "bank_account"):
        VENDORS[dup][k] = VENDORS[orig][k]
VENDORS["V052"]["ap_contact_email"] = "billing@ashbyandcrane-llp.example"
VENDORS["V052"]["counter"] = 2740


def vname(vid):
    return VENDORS[vid]["legal_name"]


def next_number(vid, step=None):
    v = VENDORS[vid]
    v["counter"] += step if step is not None else R.randint(1, 23)
    c = v["counter"]
    return {"INV": f"INV-{c:05d}", "FA": f"FA-{c:06d}", "PFX": f"{v['pfx']}26-{c:04d}", "Z6": f"{c:06d}",
            "N": str(c)}[v["style"]]


# ----------------------------------------------------------------------------------------------------------------
# Contracts (27)
# ----------------------------------------------------------------------------------------------------------------
FIXED = {  # vendor -> (monthly fee, currency, line description(s))
    "V015": ("2650.00", "CAD", "Driftwood Workspace subscription (28 seats)"),
    "V016": ("3250.00", "USD", "Corvid Analytics Business - 10 seats"),
    "V017": ("845.00", "CAD", "Fernleaf Team plan (45 users)"),
    "V018": ("2100.00", "USD", "Hightide CRM Professional (18 seats)"),
    "V019": ("1320.00", "CAD", "Abacus Ledger accounting platform"),
    "V020": ("515.00", "CAD", "Signet e-signature business plan"),
    "V021": ("4500.00", "USD", "Compute and storage - committed base"),
    "V022": ("2380.00", "CAD", "Business mobile plans (38 lines)"),
    "V023": ("940.00", "CAD", "Fibre internet 1 Gbps (Montreal office)"),
    "V034": ("3875.00", "CAD", "Commercial property and liability premium"),
    "V035": ("6240.00", "CAD", "Commercial fleet policy premium"),
    "V036": ("16150.00", "CAD", ("Base rent - Toronto HQ", "13800.00", "Operating costs (CAM) - Toronto HQ", "2350.00")),
    "V037": ("8250.00", "CAD", ("Base rent - Vancouver office", "7200.00", "Operating costs (CAM) - Vancouver", "1050.00")),
    "V038": ("10650.00", "CAD", ("Base rent - Calgary warehouse", "9400.00", "Operating costs (CAM) - Calgary", "1250.00")),
    "V039": ("6400.00", "CAD", ("Loyer de base - Montreal", "5500.00", "Frais d'exploitation - Montreal", "900.00")),
    "V045": ("2475.00", "CAD", "Janitorial services - Toronto HQ"),
    "V049": ("2310.00", "CAD", "Group benefits administration fee"),
}
CONTRACTS = []
CONTRACT_OF = {}
for i, (vid, (fee, cur, _)) in enumerate(FIXED.items(), start=1):
    cid = f"C{i:03d}"
    CONTRACTS.append(dict(contract_id=cid, vendor_id=vid, description="Monthly fixed fee agreement",
                          billing_type="fixed_monthly", monthly_fee=fee, currency=cur, hourly_rate_cap="",
                          fuel_surcharge_cap_pct="", not_to_exceed_monthly="", start_date="2026-01-01",
                          end_date="2026-12-31", auto_renew="yes", status="active"))
    CONTRACT_OF[vid] = cid
FUEL_CAP = {"V001": 12, "V002": 12, "V004": 11, "V005": 12}
RATE_CAP = {"V026": Decimal("525"), "V029": Decimal("235"), "V028": Decimal("380")}
extra = [
    ("V001", "Freight rate card with fuel surcharge cap", "rate_card", "", "12", "28000.00", "2026-01-01", "2026-12-31", "active"),
    ("V002", "Freight rate card with fuel surcharge cap", "rate_card", "", "12", "28000.00", "2026-01-01", "2026-12-31", "active"),
    ("V004", "Freight rate card with fuel surcharge cap", "rate_card", "", "11", "26000.00", "2026-01-01", "2026-12-31", "active"),
    ("V005", "Freight rate card with fuel surcharge cap", "rate_card", "", "12", "28000.00", "2026-01-01", "2026-12-31", "active"),
    ("V026", "Legal retainer with hourly rate cap", "retainer", "525.00", "", "20000.00", "2026-01-01", "2026-12-31", "active"),
    ("V029", "Consulting rate agreement", "rate_card", "235.00", "", "25000.00", "2026-01-01", "2026-12-31", "active"),
    ("V028", "Quarterly review and tax advisory", "retainer", "380.00", "", "15000.00", "2026-01-01", "2026-12-31", "active"),
    ("V044", "Maintenance services agreement", "blanket", "", "", "15000.00", "2025-07-01", "2026-06-30", "expired"),
    ("V043", "Maintenance services agreement", "blanket", "", "", "15000.00", "2026-01-01", "2026-12-31", "active"),
    ("V008", "Fleet fuel card program", "blanket", "", "", "9500.00", "2026-01-01", "2026-12-31", "active"),
]
for j, (vid, desc, bt, cap, fcap, nte, sd, ed, st) in enumerate(extra, start=len(CONTRACTS) + 1):
    cid = f"C{j:03d}"
    CONTRACTS.append(dict(contract_id=cid, vendor_id=vid, description=desc, billing_type=bt, monthly_fee="",
                          currency="CAD", hourly_rate_cap=cap, fuel_surcharge_cap_pct=fcap,
                          not_to_exceed_monthly=nte, start_date=sd, end_date=ed, auto_renew="yes", status=st))
    CONTRACT_OF[vid] = cid
NTE = {c["vendor_id"]: Decimal(c["not_to_exceed_monthly"]) for c in CONTRACTS if c["not_to_exceed_monthly"]}

# ----------------------------------------------------------------------------------------------------------------
# People helpers
# ----------------------------------------------------------------------------------------------------------------
DEPT_OF_VENDOR = {
    "freight": ["D01"], "fuelcard": ["D01"], "tires": ["D01"], "warehouse": ["D01"], "safety": ["D01", "D07"],
    "office": ["D04", "D02", "D07", "D08", "D06"], "furniture": ["D07"], "legal": ["D08", "D04", "D06"],
    "accounting": ["D04"], "consulting": ["D01", "D02", "D05"], "marketing": ["D03"], "printing": ["D03", "D02"],
    "rent": ["D07"], "utility": ["D07"], "repairs": ["D07", "D01"], "travel": ["D02", "D01", "D03", "D08", "D05"],
    "events": ["D03", "D06", "D02"], "recruiting": ["D06"], "training": ["D06"], "cloud": ["D05"],
}
FIXED_DEPT = {"V015": "D05", "V016": "D05", "V017": "D05", "V018": "D02", "V019": "D04", "V020": "D04",
              "V022": "D05", "V023": "D05", "V034": "D04", "V035": "D01", "V045": "D07", "V049": "D06"}
OFFICE_PROV = {"V036": "ON", "V037": "BC", "V038": "AB", "V039": "QC", "V040": "ON", "V041": "BC", "V042": "AB",
               "V045": "ON", "V023": "QC", "V022": "ON"}


def requesters(dept, on):
    pool = [u for u in USERS.values() if u["department_id"] == dept and u["role"] == "employee"]
    if dept == "D04":
        pool += [USERS["U004"], USERS["U005"]]
    return [u["user_id"] for u in pool
            if u["status"] != "terminated" or on < D.fromisoformat(u["status_since"])]


def pick_requester(dept, on, rng=R):
    return rng.choice(requesters(dept, on))


# ----------------------------------------------------------------------------------------------------------------
# Line generators: return [(description, qty, unit_price, line_type)]; m scales prices
# ----------------------------------------------------------------------------------------------------------------
CITIES = ["Toronto", "Montreal", "Calgary", "Vancouver", "Hamilton", "Winnipeg", "Regina", "Saskatoon", "Edmonton",
          "Ottawa", "Halifax", "Quebec City"]


def lanes(rng):
    a, b = rng.sample(CITIES, 2)
    return f"{a}-{b}"


def gen_lines(vid, inv_date, n, m, rng):
    v = VENDORS[vid]
    k = v["kind"]
    mon = MONTHS[inv_date.month - 1]
    out = []
    if k == "fixed":
        fee, _, desc = FIXED[vid]
        if isinstance(desc, tuple):
            out = [(desc[0], 1, Decimal(desc[1]), "services"), (desc[2], 1, Decimal(desc[3]), "services")]
        else:
            out = [(desc, 1, Decimal(fee), "services")]
    elif k == "rent":
        fee, _, desc = FIXED[vid]
        out = [(desc[0], 1, Decimal(desc[1]), "services"), (desc[2], 1, Decimal(desc[3]), "services")]
    elif k == "cloud":
        out = [(FIXED[vid][2], 1, Decimal(FIXED[vid][0]), "services")]
        if n > 1:
            out.append(("Usage overage - data transfer", 1, q(rng.uniform(120, 900) * m), "services"))
    elif k == "utility":
        word = "Electricity" if vid == "V040" else "Energy"
        season = 1.35 if inv_date.month in (1, 2, 3, 12) else (1.15 if inv_date.month in (7, 8) else 1.0)
        out = [(f"{word} - {mon}", 1, q(rng.uniform(1500, 3900) * season * m), "services")]
        if n > 1:
            out.append(("Delivery and regulatory charges", 1, q(rng.uniform(180, 420) * m), "services"))
    elif k == "freight":
        ships = []
        for _ in range(max(1, n - 1) if n > 1 else 1):
            ships.append((f"LTL shipment {lanes(rng)}", 1, q(rng.uniform(380, 2300) * m), "services"))
        out = ships
        if n > 1:
            cap = FUEL_CAP.get(vid, 12)
            pct = Decimal(f"{rng.uniform(6.0, cap - 0.4):.2f}")
            base = sum(s[2] for s in ships)
            out.append((f"Fuel surcharge ({pct}%)", 1, q(base * pct / 100), "services"))
    elif k == "fuelcard":
        out = [(f"Fleet fuel card charges - {mon}", 1, q(rng.uniform(3400, 7600) * m), "services")]
        for i in range(1, n):
            out.append((f"Card fees and reports - {mon}", 1, q(rng.uniform(40, 160)), "services"))
    elif k == "tires":
        opts = [("Tire service (units)", 6, 18, 240, 420, "goods"), ("Wheel alignment", 2, 8, 120, 180, "services"),
                ("Tire disposal fees", 6, 18, 6, 9, "services")]
        for o in opts[:n]:
            qty = rng.randint(o[1], o[2])
            out.append((o[0], qty, q(rng.uniform(o[3], o[4]) * m), o[5]))
    elif k == "warehouse":
        opts = [("Pallets (48x40)", 150, 650, 7.5, 11.5), ("Stretch wrap cases", 10, 60, 48, 72),
                ("Racking components", 4, 30, 85, 260), ("Corner boards (bundle)", 5, 40, 22, 38)]
        for o in rng.sample(opts, n):
            qty = rng.randint(o[1], o[2])
            desc = f"{o[0]}, qty {qty}" if o[0].startswith("Pallets") else o[0]
            out.append((desc, qty, q(rng.uniform(o[3], o[4]) * m), "goods"))
    elif k == "safety":
        opts = [("Safety boots", 8, 40, 120, 185), ("Hi-vis vests", 20, 120, 14, 24), ("First aid kits", 4, 20, 65, 140),
                ("Hard hats", 10, 60, 22, 38), ("Safety glasses (box of 12)", 4, 25, 48, 75)]
        for o in rng.sample(opts, n):
            out.append((o[0], rng.randint(o[1], o[2]), q(rng.uniform(o[3], o[4]) * m), "goods"))
    elif k == "office":
        opts = [("Printer paper cartons", 6, 40, 42, 58), ("Toner cartridges", 4, 24, 95, 160),
                ("Stationery and desk supplies", 1, 1, 180, 900), ("Breakroom supplies", 1, 1, 120, 480)]
        for o in rng.sample(opts, n):
            out.append((o[0], rng.randint(o[1], o[2]), q(rng.uniform(o[3], o[4]) * m), "goods"))
    elif k == "furniture":
        opts = [("Sit/stand desks", 3, 14, 640, 980), ("Ergonomic task chairs", 4, 20, 390, 760),
                ("Filing cabinets", 2, 10, 260, 480), ("Meeting table", 1, 2, 900, 1900)]
        for o in rng.sample(opts, n):
            out.append((o[0], rng.randint(o[1], o[2]), q(rng.uniform(o[3], o[4]) * m), "goods"))
    elif k == "legal":
        matters = ["Commercial lease review", "Carrier agreement negotiation", "Employment matter",
                   "Corporate records update", "Customs dispute", "Vendor agreement review"]
        cap = RATE_CAP.get(vid, Decimal("560"))
        rate = min(cap, q(rng.uniform(420, 560)))
        hrs = Decimal(rng.randint(6, 30)) / 2 * Decimal(str(m)).quantize(Decimal("0.1"))
        hrs = max(Decimal("1.5"), (hrs * 2).to_integral_value(ROUND_HALF_UP) / 2)
        out = [(f"Legal services - {rng.choice(matters)}", hrs, rate, "services")]
        if n > 1:
            out.append(("Disbursements and filing fees", 1, q(rng.uniform(60, 480)), "services"))
        for _ in range(2, n):
            out.append((f"Legal services - {rng.choice(matters)}", Decimal(rng.randint(2, 8)), rate, "services"))
    elif k == "accounting":
        rate = min(RATE_CAP["V028"], q(rng.uniform(340, 380)))
        out = [("Quarterly review engagement", 1, q(rng.uniform(2800, 5200) * m), "services")]
        if n > 1:
            out.append(("Tax advisory hours", Decimal(rng.randint(3, 14)), rate, "services"))
    elif k == "consulting":
        projects = ["Fleet routing study", "Process improvement", "Warehouse layout review", "CRM rollout",
                    "Network audit", "Pricing analysis"]
        cap = RATE_CAP.get(vid, Decimal("260"))
        rate = min(cap, q(rng.uniform(195, 260)))
        hrs = max(Decimal("4"), (Decimal(rng.randint(12, 34)) * Decimal(str(m)) * 4).to_integral_value(ROUND_HALF_UP) / 4)
        out = [(f"Consulting hours - {rng.choice(projects)}", hrs, rate, "services")]
        if n > 1:
            out.append(("Workshop facilitation", 1, q(rng.uniform(900, 2200)), "services"))
        for _ in range(2, n):
            out.append(("Travel and expenses (at cost)", 1, q(rng.uniform(150, 700)), "services"))
    elif k == "marketing":
        camps = ["Spring promotion", "Trade show", "Search ads", "Fall launch", "Customer webinar series",
                 "Holiday campaign", "Social media boost"]
        out = [(f"Digital ad campaign - {rng.choice(camps)}", 1, q(rng.uniform(1800, 6200) * m), "services")]
        for _ in range(1, n):
            out.append(("Creative design services", 1, q(rng.uniform(600, 2400) * m), "services"))
    elif k == "printing":
        opts = [("Printed brochures", 500, 5000, 0.35, 0.9), ("Branded signage", 2, 12, 140, 480),
                ("Trade show banners", 1, 6, 180, 420), ("Business cards (boxes)", 5, 40, 28, 45)]
        for o in rng.sample(opts, n):
            out.append((o[0], rng.randint(o[1], o[2]), q(rng.uniform(o[3], o[4]) * m), "goods"))
    elif k == "repairs":
        opts = [("HVAC service call", 1, 1, 420, 1800, "services"), ("Dock door repair", 1, 1, 650, 2600, "services"),
                ("Forklift repair parts", 1, 6, 90, 480, "goods"), ("Dock leveler repair", 1, 1, 800, 2900, "services"),
                ("Lighting retrofit labour", 4, 16, 95, 120, "services")]
        for o in rng.sample(opts, n):
            out.append((o[0], rng.randint(o[1], o[2]), q(rng.uniform(o[3], o[4]) * m), o[5]))
    elif k == "travel":
        for _ in range(n):
            out.append((f"Airfare - {lanes(rng)}", 1, q(rng.uniform(290, 1350) * m), "services"))
    elif k == "events":
        opts = [("Team event catering", 1, 1, 900, 3400), ("Venue rental", 1, 1, 600, 2200),
                ("AV equipment rental", 1, 1, 250, 900), ("Event staffing (hours)", 6, 24, 38, 52)]
        for o in rng.sample(opts, n):
            out.append((o[0], rng.randint(o[1], o[2]), q(rng.uniform(o[3], o[4]) * m), "services"))
    elif k == "recruiting":
        roles = ["Fleet Planner", "Sales Executive", "Warehouse Supervisor", "Senior Accountant", "Dispatcher",
                 "Data Analyst"]
        out = [(f"Placement fee - {rng.choice(roles)}", 1, q(rng.uniform(6500, 9800) * m), "services")]
        for _ in range(1, n):
            out.append((f"Placement fee - {rng.choice(roles)}", 1, q(rng.uniform(6500, 9800) * m), "services"))
    elif k == "training":
        out = [("Compliance training licences", rng.randint(10, 40), q(rng.uniform(38, 72) * m), "services")]
        if n > 1:
            out.append(("Instructor-led session", 1, q(rng.uniform(400, 1200)), "services"))
    return out


MULTI_OK = {"freight": (1, 5), "warehouse": (1, 4), "safety": (1, 4), "office": (1, 4), "furniture": (1, 3),
            "legal": (1, 3), "accounting": (1, 2), "consulting": (1, 3), "marketing": (1, 3), "printing": (1, 3),
            "repairs": (1, 3), "travel": (1, 5), "events": (1, 3), "recruiting": (1, 2), "training": (1, 2),
            "tires": (1, 3), "fuelcard": (1, 2), "utility": (1, 2), "cloud": (1, 2)}
FIXED_N = {"fixed": None, "rent": 2}


def base_nlines(vid, rng):
    k = VENDORS[vid]["kind"]
    if k in ("fixed", "rent"):
        return len(gen_lines(vid, D(2026, 1, 1), 1, 1, rng))
    lo, hi = MULTI_OK[k]
    if k == "freight":
        return rng.choice([1, 2, 3, 3, 4])
    if k == "cloud":
        return rng.choice([1, 1, 2])
    if k == "utility":
        return 1
    return rng.randint(lo, min(hi, 2))


# ----------------------------------------------------------------------------------------------------------------
# Tax
# ----------------------------------------------------------------------------------------------------------------


def tax_codes_for(vid, prov, line_type):
    v = VENDORS[vid]
    if v["country"] == "US":
        return "FOREIGN", ""
    if v["category"] == "Insurance":
        return "EXEMPT", ""
    if not v["gst_hst_number"]:
        return "", ""
    g = "HST13" if prov == "ON" else "GST5"
    p = ""
    if prov == "QC":
        p = "QST9975"
    elif prov == "BC" and line_type == "goods":
        p = "PST7_BC"
    return g, p


RATES = {"HST13": Decimal("0.13"), "GST5": Decimal("0.05"), "QST9975": Decimal("0.09975"), "PST7_BC": Decimal("0.07")}


def compute_taxes(lines):
    """lines: dicts with amount, gst_hst_code, pst_qst_code. Returns ({code: amount}, gst_total, pst_total)."""
    base = defaultdict(Decimal)
    for l in lines:
        if l["gst_hst_code"] in RATES:
            base[l["gst_hst_code"]] += l["amount"]
        if l["pst_qst_code"] in RATES:
            base[l["pst_qst_code"]] += l["amount"]
    taxes = {c: q(b * RATES[c]) for c, b in base.items()}
    gst = sum((a for c, a in taxes.items() if c in ("HST13", "GST5")), Decimal("0"))
    pst = sum((a for c, a in taxes.items() if c in ("QST9975", "PST7_BC")), Decimal("0"))
    return taxes, q(gst), q(pst)


def ship_province(vid, requester):
    return OFFICE_PROV.get(vid) or USERS[requester]["work_province"]


def build_invoice(plan, m=1.0):
    """Materialise lines and totals for a plan. Deterministic per plan seed."""
    rng = random.Random(plan["seed"])
    vid = plan["vendor_id"]
    raw = gen_lines(vid, plan["invoice_date"], plan["nlines"], m, rng)
    prov = plan["ship_to_province"]
    gl = VENDORS[vid]["default_gl_account"]
    lines = []
    for i, (desc, qty, unit, lt) in enumerate(raw, start=1):
        qty = Decimal(str(qty))
        amt = q(qty * unit)
        g, p = tax_codes_for(vid, prov, lt)
        lines.append(dict(line_no=i, description=desc, quantity=qty, unit_price=q(unit), amount=amt, gl_account=gl,
                          department_id=plan["department_id"], gst_hst_code=g, pst_qst_code=p, line_type=lt))
    subtotal = q(sum(l["amount"] for l in lines))
    _, gst, pst = compute_taxes(lines)
    total = q(subtotal + gst + pst)
    cur = FIXED[vid][1] if vid in FIXED else ("USD" if VENDORS[vid]["country"] == "US" else "CAD")
    fx = FX[plan["invoice_date"]] if cur == "USD" else Decimal("1")
    return dict(lines=lines, subtotal=subtotal, gst=gst, pst=pst, total=total, currency=cur, fx=fx,
                total_cad=q(total * fx))


# ----------------------------------------------------------------------------------------------------------------
# History plans (Jan-Oct 2026)
# ----------------------------------------------------------------------------------------------------------------
PLANS = []


def plan(vid, inv_date, cls="normal", dept=None, received=None, nlines=None, **kw):
    v = VENDORS[vid]
    dept = dept or FIXED_DEPT.get(vid) or R.choice(DEPT_OF_VENDOR[v["kind"]])
    req = pick_requester(dept, inv_date)
    p = dict(vendor_id=vid, invoice_date=inv_date, cls=cls, department_id=dept, requester_user_id=req,
             received_date=received or add(inv_date, R.choice([0, 0, 1, 1, 2, 3])), seed=R.randint(1, 10 ** 9),
             nlines=nlines if nlines is not None else base_nlines(vid, R), ship_to_province=None, **kw)
    p["ship_to_province"] = ship_province(vid, req)
    PLANS.append(p)
    return p


monthly_day = {vid: R.randint(1, 9) for vid in FIXED}
for vid in FIXED:
    for mo in range(1, 11):
        day = 1 if VENDORS[vid]["kind"] == "rent" else monthly_day[vid]
        plan(vid, D(2026, mo, day), received=D(2026, mo, day) if VENDORS[vid]["kind"] == "rent" else None)
for vid in ("V021",):
    for mo in range(1, 11):
        plan(vid, D(2026, mo, R.randint(1, 4)), nlines=R.choice([1, 2, 2]))
for vid, day in (("V040", 14), ("V041", 17), ("V042", 11)):
    for mo in range(1, 11):
        plan(vid, D(2026, mo, day + R.randint(-2, 2)))
for vid in ("V007", "V008"):
    for mo in range(1, 11):
        plan(vid, D(2026, mo, R.randint(3, 8)), nlines=R.choice([1, 1, 2]))

VARIABLE_COUNTS = {"V001": 12, "V002": 12, "V003": 10, "V004": 12, "V005": 11, "V006": 13, "V051": 3, "V053": 2,
                   "V009": 6, "V010": 5, "V011": 6, "V012": 3, "V013": 4, "V014": 3, "V024": 10, "V025": 4,
                   "V026": 4, "V027": 4, "V028": 5, "V029": 2, "V030": 5, "V031": 6, "V032": 6, "V033": 5,
                   "V043": 6, "V044": 6, "V046": 8, "V047": 4, "V048": 5, "V050": 3}
for vid, n in VARIABLE_COUNTS.items():
    lo = D(2026, 1, 2)
    hi = D(2026, 10, 22)
    if vid == "V053":
        hi = D(2026, 3, 31)  # put on hold in spring
    if vid == "V051":
        lo = D(2026, 4, 1)
    for _ in range(n):
        plan(vid, rdate(lo, hi))

PADDABLE = ["V001", "V002", "V003", "V004", "V005", "V006", "V009", "V024", "V030", "V031", "V032", "V033",
            "V048", "V028"]
EXTRA_PLANS = 3  # 2 rejected duplicates + 1 scheduled duplicate, added later
while len(PLANS) + EXTRA_PLANS < TARGET["invoices"]:
    plan(R.choice(PADDABLE), rdate(D(2026, 1, 2), D(2026, 10, 22)))
while len(PLANS) + EXTRA_PLANS > TARGET["invoices"]:
    cands = [p for p in PLANS if p["vendor_id"] in PADDABLE]
    PLANS.remove(R.choice(cands))

BIG_OK = {"V001", "V002", "V003", "V004", "V005", "V006", "V010", "V011", "V025", "V030", "V031", "V032", "V048"}


def class_target(p, inv):
    """Scale an invoice so 'normal' stays under $10k CAD and 'big' lands above it."""
    if VENDORS[p["vendor_id"]]["kind"] in ("fixed", "rent"):
        return 1.0
    tc = float(inv["total_cad"])
    if p["cls"] == "big":
        if tc < 10600:
            return R.uniform(10800, 15500 if VENDORS[p["vendor_id"]]["kind"] == "freight" else 21000) / tc
        return 1.0
    cap = 9400
    if VENDORS[p["vendor_id"]]["kind"] == "repairs":
        cap = 6500
    return (cap * R.uniform(0.45, 0.95)) / tc if tc > cap else 1.0


def materialise(p):
    inv = build_invoice(p, 1.0)
    m = class_target(p, inv)
    if m != 1.0:
        inv = build_invoice(p, m)
    p["inv"] = inv


# Line-count tuning happens before amounts. Extra plans add: 1+1+? lines (copies) -> handled after.
def total_lines(extra_lines=0):
    return sum(p["nlines"] for p in PLANS) + extra_lines


# ----------------------------------------------------------------------------------------------------------------
# Assemble history: amounts, statuses, approvals
# ----------------------------------------------------------------------------------------------------------------
PLANS.sort(key=lambda p: (p["invoice_date"], p["received_date"], p["vendor_id"]))

# Rejections: 10 regular + 2 'duplicate already submitted' copies.
REJECT_REASONS = ["Coding incorrect, resubmit", "Goods not received", "Pricing does not match quote",
                  "Wrong cost centre", "Missing PO reference", "Services not yet delivered"]
eligible_reject = [p for p in PLANS if VENDORS[p["vendor_id"]]["kind"] not in ("fixed", "rent", "legal", "utility")
                   and VENDORS[p["vendor_id"]]["requires_po"] == "no" and p["invoice_date"] <= D(2026, 10, 10)
                   and p["vendor_id"] not in ("V053", "V051")]
for p in R.sample(eligible_reject, 10):
    p["rejected"] = R.choice(REJECT_REASONS)

# Promote enough invoices to 'big' that exactly 48 finance-manager steps exist (474 approvals = 426 + 48).
for p in PLANS:
    materialise(p)


def needs_fm(p):
    v = VENDORS[p["vendor_id"]]
    return not p.get("rejected") and (p["inv"]["total_cad"] > 10000 or v["category"] == "Legal")


# Line count: tune variable invoices so the final total is exactly 809 (the 3 copies add their source's lines).
def tune_lines(extra):
    tunable = [p for p in PLANS if VENDORS[p["vendor_id"]]["kind"] in MULTI_OK
               and VENDORS[p["vendor_id"]]["kind"] not in ("utility", "cloud")]
    guard = 0
    while total_lines(extra) != TARGET["invoice_lines"] and guard < 10000:
        guard += 1
        p = R.choice(tunable)
        lo, hi = MULTI_OK[VENDORS[p["vendor_id"]]["kind"]]
        if total_lines(extra) < TARGET["invoice_lines"] and p["nlines"] < hi:
            p["nlines"] += 1
        elif total_lines(extra) > TARGET["invoice_lines"] and p["nlines"] > lo:
            p["nlines"] -= 1
    assert total_lines(extra) == TARGET["invoice_lines"], total_lines(extra)


# Pick the 3 copies' sources now (their line counts are added on top).
paid_window = [p for p in PLANS if not p.get("rejected") and D(2026, 2, 1) <= p["invoice_date"] <= D(2026, 8, 31)
               and VENDORS[p["vendor_id"]]["kind"] in ("freight", "office", "marketing", "printing")
               and VENDORS[p["vendor_id"]]["requires_po"] == "no"]
dup_rej_sources = R.sample(paid_window, 2)
sched_dup_source = R.choice([p for p in PLANS if p["vendor_id"] == "V048" and not p.get("rejected")] or
                            [p for p in PLANS if p["vendor_id"] in ("V030", "V031")])
sched_dup_source["invoice_date"] = D(2026, 10, 16)
sched_dup_source["received_date"] = D(2026, 10, 16)
sched_dup_source["cls"] = "big"
sched_dup_source.pop("rejected", None)
tune_lines(sum(p["nlines"] for p in dup_rej_sources) + sched_dup_source["nlines"])
for p in PLANS:
    materialise(p)

fm_fixed = 0


def fm_count():
    # copies: rejected ones add no FM; the scheduled copy adds one if its source needs it
    return sum(1 for p in PLANS if needs_fm(p)) + (1 if needs_fm(sched_dup_source) else 0)


def month_spend(vid, month):
    return sum(x["inv"]["subtotal"] for x in PLANS if x["vendor_id"] == vid and x["invoice_date"].month == month)


def nte_ok(p):
    cap = NTE.get(p["vendor_id"])
    return cap is None or month_spend(p["vendor_id"], p["invoice_date"].month) <= cap * Decimal("0.9")


# Contracted vendors: keep each month's spend under the contract cap (history should be clean).
for p in PLANS:
    tries = 0
    while not nte_ok(p) and tries < 20:
        tries += 1
        p["cls"] = "normal"
        p["nlines"] = max(1, p["nlines"] - 1) if tries > 3 else p["nlines"]
        p["seed"] = R.randint(1, 10 ** 9)
        materialise(p)

guard = 0
while fm_count() != TARGET["approvals"] - TARGET["invoices"] and guard < 5000:
    guard += 1
    need = TARGET["approvals"] - TARGET["invoices"] - fm_count()
    pool = [p for p in PLANS if p["vendor_id"] in BIG_OK and not p.get("rejected") and p is not sched_dup_source
            and p not in dup_rej_sources]
    if need > 0:
        p = R.choice([p for p in pool if p["cls"] == "normal"])
        p["cls"] = "big"
        materialise(p)
        if not nte_ok(p):
            p["cls"] = "normal"
            materialise(p)
    else:
        p = R.choice([p for p in pool if p["cls"] == "big"])
        p["cls"] = "normal"
        materialise(p)
assert fm_count() == TARGET["approvals"] - TARGET["invoices"], fm_count()


def actual_lines():
    srcs = dup_rej_sources + [sched_dup_source]
    return sum(len(p["inv"]["lines"]) for p in PLANS) + sum(len(p["inv"]["lines"]) for p in srcs)


guard = 0
while actual_lines() != TARGET["invoice_lines"] and guard < 5000:
    guard += 1
    p = R.choice([p for p in PLANS if VENDORS[p["vendor_id"]]["kind"] in ("travel", "office", "safety", "printing")
                  and p not in dup_rej_sources and p is not sched_dup_source])
    lo, hi = MULTI_OK[VENDORS[p["vendor_id"]]["kind"]]
    step = 1 if actual_lines() < TARGET["invoice_lines"] else -1
    if lo <= p["nlines"] + step <= hi:
        p["nlines"] += step
        materialise(p)
assert actual_lines() == TARGET["invoice_lines"], actual_lines()
assert fm_count() == TARGET["approvals"] - TARGET["invoices"], fm_count()
for p in PLANS:
    assert NTE.get(p["vendor_id"]) is None or month_spend(p["vendor_id"], p["invoice_date"].month) <= NTE[p["vendor_id"]]

# Create the copies.
copies = []
for src in dup_rej_sources:
    c = dict(src)
    c["received_date"] = add(src["received_date"], R.randint(9, 20))
    c["rejected"] = "Duplicate - already submitted"
    c["copy_of"] = src
    copies.append(c)
c = dict(sched_dup_source)
c["received_date"] = add(sched_dup_source["received_date"], 6)
c["copy_of"] = sched_dup_source
copies.append(c)
PLANS.extend(copies)
PLANS.sort(key=lambda p: (p["invoice_date"], p["received_date"], p["vendor_id"]))
assert len(PLANS) == TARGET["invoices"], len(PLANS)

# Ids, invoice numbers
for i, p in enumerate(PLANS, start=1):
    p["invoice_id"] = f"AP-{i:05d}"
for p in sorted(PLANS, key=lambda p: (p["invoice_date"], p["invoice_id"])):
    if "copy_of" in p:
        continue
    p["vendor_invoice_number"] = next_number(p["vendor_id"])
for p in PLANS:
    if "copy_of" in p:
        p["vendor_invoice_number"] = p["copy_of"]["vendor_invoice_number"]

# ----------------------------------------------------------------------------------------------------------------
# POs
# ----------------------------------------------------------------------------------------------------------------
POS = []


def new_po(vid, dept, req, issue, total, status, desc=None):
    POS.append(dict(vendor_id=vid, department_id=dept, requester_user_id=req, issue_date=issue,
                    description=desc or f"{VENDORS[vid]['category']} - {vname(vid)}", total_amount=q(total),
                    currency="CAD", status=status, approved_by=DEPTS[dept]["manager_user_id"]))
    return POS[-1]


for p in PLANS:
    if VENDORS[p["vendor_id"]]["requires_po"] == "yes" and "copy_of" not in p:
        po = new_po(p["vendor_id"], p["department_id"], p["requester_user_id"],
                    add(p["invoice_date"], -R.randint(4, 21)), p["inv"]["subtotal"], "closed")
        p["po"] = po

# Open POs referenced by the incoming batch (filled in below) are created there; padding happens at the end.

# ----------------------------------------------------------------------------------------------------------------
# Approvals, statuses, payments
# ----------------------------------------------------------------------------------------------------------------


def at(day, lo=8, hi=17):
    return dt.datetime.combine(day, dt.time(R.randint(lo, hi - 1), R.randint(0, 59)))


LIAM_LEAVE = D.fromisoformat(USERS["U012"]["status_since"])
APPROVALS = []
for p in PLANS:
    dept_mgr = DEPTS[p["department_id"]]["manager_user_id"]
    t1 = at(add(p["received_date"], R.choice([0, 0, 1, 1, 2])))
    if t1.date() > D(2026, 10, 31):
        t1 = at(D(2026, 10, 31))
    approver = dept_mgr
    if dept_mgr == "U012" and t1.date() >= LIAM_LEAVE:
        approver = "U003"  # delegate while Liam is on leave
    steps = []
    if p.get("rejected"):
        steps.append((approver, "dept_manager", "rejected", t1, p["rejected"]))
    else:
        steps.append((approver, "dept_manager", "approved", t1, ""))
        if needs_fm(p):
            fm = "U003" if approver != "U003" else "U002"
            t2 = t1 + dt.timedelta(hours=R.randint(1, 30))
            steps.append((fm, "finance_manager", "approved", t2, ""))
    p["approvers"] = {s[0] for s in steps}
    p["approved_at"] = steps[-1][3]
    for n, (u, role, act, t, cmt) in enumerate(steps, start=1):
        APPROVALS.append(dict(invoice_id=p["invoice_id"], step_no=n, approver_user_id=u, approver_role=role,
                              action=act, acted_at=t.strftime("%Y-%m-%dT%H:%M:00"), comment=cmt))
assert len(APPROVALS) == TARGET["approvals"], len(APPROVALS)
for i, a in enumerate(APPROVALS, start=1):
    a["approval_id"] = f"AP-A-{i:05d}"

for p in PLANS:
    terms = VENDORS[p["vendor_id"]]["payment_terms_days"]
    p["due_date"] = add(p["invoice_date"], terms)

open_plans = [p for p in PLANS if not p.get("rejected") and p["due_date"] > D(2026, 10, 31)]
latest = sorted([p for p in open_plans if "copy_of" not in p and p is not sched_dup_source
                 and VENDORS[p["vendor_id"]]["kind"] == "freight"],
                key=lambda p: p["received_date"])[-2:]
for p in latest:
    p["status"] = "approved"


def releaser(p):
    for u in ("U003", "U002", "U001"):
        if u not in p["approvers"]:
            return u


PAYMENTS = []
CARD_AP = []
refs = set()


def ref(kind):
    while True:
        r = f"BRIM-{kind}-{R.randint(10_000_000, 99_999_999)}"
        if r not in refs and r[-6:] not in {x[-6:] for x in refs}:
            refs.add(r)
            return r


def pay(p, amount, paid, status, discount=Decimal("0"), sched=None):
    v = VENDORS[p["vendor_id"]]
    inv = p["inv"]
    kind = {"EFT": "EFT", "CARD": "VC", "CHEQUE": "CHQ"}[v["preferred_payment_method"]]
    rec = dict(invoice_id=p["invoice_id"], vendor_id=p["vendor_id"], method=v["preferred_payment_method"],
               scheduled_date=sched or p["due_date"], paid_date=paid, currency=inv["currency"], amount=q(amount),
               fx_rate_to_cad=inv["fx"], amount_cad=q(amount * inv["fx"]), discount_taken=discount,
               reference=ref(kind), status=status, prepared_by=R.choice(["U004", "U005"]),
               released_by=releaser(p) if status != "scheduled" else "")
    PAYMENTS.append(rec)
    return rec


split_cands = [p for p in PLANS if not p.get("rejected") and VENDORS[p["vendor_id"]]["preferred_payment_method"] == "EFT"
               and "copy_of" not in p and Decimal("2500") < p["inv"]["total"] < Decimal("7000")
               and D(2026, 3, 1) <= p["due_date"] <= D(2026, 9, 30) and VENDORS[p["vendor_id"]]["early_pay_discount"] == ""]
SPLITS = set(id(p) for p in R.sample(split_cands, 3))

for p in PLANS:
    if p.get("rejected") or p.get("status") == "approved":
        p.setdefault("status", "rejected" if p.get("rejected") else "approved")
        continue
    v = VENDORS[p["vendor_id"]]
    inv = p["inv"]
    method = v["preferred_payment_method"]
    earliest = add(p["approved_at"].date(), 1)
    if p["due_date"] > D(2026, 10, 31) or p is sched_dup_source or "copy_of" in p:
        p["status"] = "scheduled"
        pay(p, inv["total"], "", "scheduled", sched=max(p["due_date"], AS_OF) if p["due_date"] < AS_OF else p["due_date"])
        continue
    p["status"] = "paid"
    if v["early_pay_discount"] and R.random() < 0.6 and inv["currency"] == "CAD":
        paid = max(earliest, add(p["invoice_date"], R.randint(6, 9)))
        disc = q(inv["subtotal"] * Decimal("0.02"))
        pay(p, inv["total"] - disc, paid, "settled", discount=disc)
        p["discounted"] = True
        continue
    if v["payment_terms_days"] == 0:
        paid = max(earliest, add(p["invoice_date"], R.randint(2, 4)))
    else:
        paid = max(earliest, add(p["due_date"], R.randint(-2, 3)))
    paid = min(paid, D(2026, 10, 31))
    status = "outstanding" if method == "CHEQUE" else "settled"
    if id(p) in SPLITS:
        first = q(inv["total"] * Decimal(R.choice(["0.6", "0.5", "0.55"])))
        pay(p, first, paid, status)
        pay(p, inv["total"] - first, min(add(paid, R.randint(12, 16)), D(2026, 10, 31)), status)
    else:
        pay(p, inv["total"], paid, status)

assert len(PAYMENTS) == TARGET["payments"], len(PAYMENTS)
for i, r in enumerate(PAYMENTS, start=1):
    r["payment_id"] = f"PAY-{i:05d}"

# ----------------------------------------------------------------------------------------------------------------
# Bank statement
# ----------------------------------------------------------------------------------------------------------------
STMT = []
pay_by_inv = defaultdict(list)
for r in PAYMENTS:
    pay_by_inv[r["invoice_id"]].append(r)
plan_by_id = {p["invoice_id"]: p for p in PLANS}

eft_settled = [r for r in PAYMENTS if r["method"] == "EFT" and r["status"] == "settled"]
penny = set(r["payment_id"] for r in R.sample(eft_settled, 3))
for r in eft_settled:
    amt = r["amount"] + (Decimal("0.01") if r["payment_id"] in penny else 0)
    STMT.append(dict(posted_date=add(r["paid_date"], 1),
                     description=f"EFT PMT {vname(r['vendor_id']).upper()[:24]} {r['reference'][-6:]}",
                     debit=amt, credit=""))
cheques = sorted([r for r in PAYMENTS if r["method"] == "CHEQUE" and r["status"] == "outstanding"],
                 key=lambda r: (r["paid_date"], r["payment_id"]))
rent_cheques = [r for r in cheques if r["vendor_id"] == "V039" and r["paid_date"].month in (8, 9)]
cheque_off = rent_cheques[-1]["payment_id"] if rent_cheques else None
CHEQUE_NO = {}
for n, r in enumerate(cheques, start=6001):
    CHEQUE_NO[r["payment_id"]] = n
    clear = add(r["paid_date"], R.randint(5, 24))
    if clear > AS_OF:
        continue  # still outstanding at the bank
    amt = r["amount"] + (Decimal("0.05") if r["payment_id"] == cheque_off else 0)
    STMT.append(dict(posted_date=clear, description=f"CHEQUE {n} CLEARED", debit=amt, credit=""))
for mo in range(1, 11):
    STMT.append(dict(posted_date=D(2026, mo, 28), description="MONTHLY ACCOUNT FEE", debit=Decimal("32.00"),
                     credit=""))
pad = TARGET["bank_statement"] - len(STMT)
assert pad >= 6, f"statement already has {len(STMT)} lines"
n_wire = min(9, pad // 2)
for _ in range(n_wire):
    STMT.append(dict(posted_date=rdate(D(2026, 1, 10), D(2026, 10, 30)), description="WIRE FEE",
                     debit=Decimal("17.50"), credit=""))
for _ in range(pad - n_wire):
    STMT.append(dict(posted_date=rdate(D(2026, 2, 1), D(2026, 10, 30)),
                     description=f"PREAUTH DEBIT {R.randint(100000, 999999)}", debit=q(R.uniform(85, 460)),
                     credit=""))
STMT.sort(key=lambda s: (s["posted_date"], s["description"]))
STMT = [s for s in STMT if s["posted_date"] <= AS_OF]
assert len(STMT) == TARGET["bank_statement"], len(STMT)
for i, s in enumerate(STMT, start=1):
    s["bank_txn_id"] = f"BK-{i:05d}"

# ----------------------------------------------------------------------------------------------------------------
# Card transactions
# ----------------------------------------------------------------------------------------------------------------
MCC = {"Software & SaaS": "5734", "Cloud Hosting": "7372", "Travel": "4722", "Training & Compliance": "8299",
       "Office Supplies": "5943", "Events & Catering": "5811"}
CARDS = []
for r in PAYMENTS:
    if r["method"] == "CARD" and r["status"] == "settled":
        v = VENDORS[r["vendor_id"]]
        CARDS.append(dict(card_id=f"VC-{r['invoice_id']}", cardholder_user_id=r["prepared_by"], posted_date=r["paid_date"],
                          merchant_descriptor=v["legal_name"].upper()[:22], mcc=MCC.get(v["category"], "5999"),
                          amount=r["amount"], currency=r["currency"], amount_cad=r["amount_cad"],
                          source="ap_virtual_card", receipt_attached="yes", memo=f"AP payment {r['invoice_id']}"))

ACTIVE = [u for u in USERS.values() if u["status"] != "terminated"]
CARDHOLDERS = [u["user_id"] for u in USERS.values()]


def emp_card(user, day, merchant, mcc, amount, currency="CAD", receipt=None):
    fx = FX[day] if currency == "USD" else Decimal("1")
    CARDS.append(dict(card_id=f"CARD-{user}", cardholder_user_id=user, posted_date=day, merchant_descriptor=merchant,
                      mcc=mcc, amount=q(amount), currency=currency, amount_cad=q(Decimal(amount) * fx),
                      source="employee_card", receipt_attached=receipt or ("yes" if R.random() < 0.88 else "no"),
                      memo=""))


# Planted: employees paid some Inkwell / Cedar & Sage bills on their own cards, and AP paid them again by EFT.
dbl = [p for p in PLANS if p["vendor_id"] == "V024" and p.get("discounted")][:4]
dbl += [p for p in PLANS if p["vendor_id"] == "V047" and p["status"] == "paid"][:2]
for p in dbl:
    u = pick_requester(p["department_id"], p["invoice_date"])
    emp_card(u, add(p["invoice_date"], R.randint(1, 6)), vname(p["vendor_id"]).upper()[:22], "5999",
             p["inv"]["total"], receipt="yes")
# For the incoming Inkwell invoice that was already paid by card (spec below).
INKWELL_CARD = dict(user="U036", day=D(2026, 10, 15), amount=None)

MERCHANTS = [("BREWHOUSE COFFEE CO", "5814", 8, 46, 0), ("NORTHSKY AIRLINES", "4511", 240, 1250, 0.08),
             ("SUMMIT CONFERENCE REG", "7399", 150, 950, 0.15), ("HAMMERLINE HARDWARE", "5251", 18, 420, 0),
             ("LAKEVIEW HOTEL & SUITES", "7011", 160, 640, 0.05), ("OFFICEMART STATIONERS", "5943", 14, 260, 0),
             ("CITYPARK PARKING", "7523", 8, 48, 0), ("SMOKEHOUSE GRILL", "5812", 38, 280, 0),
             ("CLOUDKIT ONLINE SVCS", "5734", 9, 160, 0.4), ("SWIFTSHIP COURIER", "4215", 18, 130, 0),
             ("METRORIDE TAXI", "4121", 11, 85, 0), ("TRAINLINK RAIL", "4112", 55, 320, 0)]
WEIGHTS = [60, 55, 48, 50, 46, 45, 44, 42, 40, 40, 38, 25]
need_emp = TARGET["card_transactions"] - len(CARDS) - 1  # -1 for the Inkwell card charge added with the spec
assert need_emp > 0
for _ in range(need_emp):
    mname, mcc, lo, hi, usd = R.choices(MERCHANTS, WEIGHTS)[0]
    u = R.choice(ACTIVE)["user_id"]
    day = rdate(D(2026, 1, 2), D(2026, 10, 31))
    cur = "USD" if R.random() < usd else "CAD"
    emp_card(u, day, mname, mcc, R.uniform(lo, hi), cur)
# U015 (terminated 2026-09-15) has a few card charges after termination: a control issue to find.
for day in (D(2026, 9, 22), D(2026, 10, 3)):
    CARDS[-1 if day.day == 22 else -2].update(card_id="CARD-U015", cardholder_user_id="U015", posted_date=day)

# ----------------------------------------------------------------------------------------------------------------
# Bank change history (9): 8 verified through the year, 1 by email and never verified (V004, 2026-10-28)
# ----------------------------------------------------------------------------------------------------------------
BC = []
bc_vendors = ["V043", "V030", "V010", "V026", "V037", "V033", "V040", "V032"]
bc_dates = sorted(rdate(D(2026, 1, 8), D(2026, 8, 28)) for _ in bc_vendors)
for vid, day in zip(bc_vendors, bc_dates):
    BC.append(dict(vendor_id=vid, changed_at=day, changed_by_user_id=R.choice(["U004", "U005"]),
                   request_channel=R.choice(["phone_callback_verified", "phone_callback_verified", "vendor_portal"]),
                   verified_by_callback="yes", old_account_last4=f"{R.randint(0, 9999):04d}",
                   new_account_last4=VENDORS[vid]["bank_account"][-4:]))
    VENDORS[vid]["bank_details_updated_at"] = day.isoformat()
COASTLINE_OLD = VENDORS["V004"]["bank_account"]
VENDORS["V004"].update(bank_institution="002", bank_transit=f"{R.randint(0, 99999):05d}",
                       bank_account=str(R.randint(100_000_000, 999_999_999)),
                       bank_details_updated_at="2026-10-28")
BC.append(dict(vendor_id="V004", changed_at=D(2026, 10, 28), changed_by_user_id="U005", request_channel="email_request",
               verified_by_callback="no", old_account_last4=COASTLINE_OLD[-4:],
               new_account_last4=VENDORS["V004"]["bank_account"][-4:]))
for i, b in enumerate(BC, start=1):
    b["change_id"] = f"BC-{i:03d}"

# ----------------------------------------------------------------------------------------------------------------
# Incoming batch: 56 files, 59 emails (spec for the PDFs; nothing goes into the history tables)
# ----------------------------------------------------------------------------------------------------------------
INCOMING = []


def office_of(prov):
    return OFFICES.get(prov, OFFICES["ON"])


def inc(vid, inv_date, lines, *, dept, requester=None, ship=None, number=None, terms=None, po=None, issue="",
        expect="", file=None, lang="en", doc="invoice", remit=None, received=None, scan=False, overrides=None,
        tax_override=None, vendor_block=None, currency=None, subject_style=None, body=None, note=""):
    """lines: [(desc, qty, unit, line_type)]. tax_override: [(code, base_selector)] replaces default codes."""
    v = VENDORS.get(vid) if vid else None
    vb = vendor_block or dict(name=v["legal_name"], address=v["address"], gst=v["gst_hst_number"], qst=v["qst_number"],
                              email=v["ap_contact_email"], province=v["province_or_state"], country=v["country"])
    requester = requester or pick_requester(dept, inv_date)
    ship = ship or USERS[requester]["work_province"]
    cur = currency or (("USD" if v["country"] == "US" else "CAD") if v else "CAD")
    out = []
    for i, (desc, qty, unit, lt) in enumerate(lines, start=1):
        qty = Decimal(str(qty))
        unit = Decimal(str(unit))
        amt = q(qty * unit)
        if vid:
            g, p = tax_codes_for(vid, ship, lt)
        else:
            g, p = ("HST13" if ship == "ON" else "GST5"), ("QST9975" if ship == "QC" else "")
        if tax_override:
            g, p = tax_override(lt)
        out.append(dict(line_no=i, description=desc, quantity=qty, unit_price=q(unit), amount=amt, line_type=lt,
                        gst_hst_code=g, pst_qst_code=p))
    if overrides and "line_amounts" in overrides:
        for ln, amt in overrides["line_amounts"].items():
            out[ln - 1]["printed_amount"] = Decimal(amt)
    subtotal = q(sum(l.get("printed_amount", l["amount"]) for l in out))
    taxes, gst, pst = compute_taxes([dict(l, amount=l.get("printed_amount", l["amount"])) for l in out])
    total = q(subtotal + gst + pst)
    printed_total = q(total + Decimal(overrides["total_delta"])) if overrides and "total_delta" in overrides else total
    terms = terms if terms is not None else (v["payment_terms_days"] if v else 30)
    number = number or next_number(vid)
    fx = FX[inv_date] if cur == "USD" else Decimal("1")
    rec = dict(vendor_id=vid, vendor=vb, invoice_number=number, invoice_date=inv_date, due_date=add(inv_date, terms),
               terms_days=terms, currency=cur, po_number=po, department_id=dept, requester_user_id=requester,
               ship_to_province=ship, ship_to=office_of(ship), lines=out, subtotal=subtotal, taxes=taxes,
               total=printed_total, correct_total=total, fx_rate_to_cad=fx if cur == "USD" else None,
               language=lang, document_type=doc, scan=scan, issue=issue, expect=expect, note=note,
               remit=remit or ({"institution": v["bank_institution"], "transit": v["bank_transit"],
                                "account": v["bank_account"]} if v and v["bank_account"] else None),
               received=received, file=file, subject_style=subject_style, body=body)
    INCOMING.append(rec)
    return rec


def hist(vid, pred=lambda p: True, status="paid"):
    c = [p for p in PLANS if p["vendor_id"] == vid and p["status"] == status and "copy_of" not in p and pred(p)]
    return c[-1]


def copy_hist(p, **kw):
    lines = [(l["description"], l["quantity"], l["unit_price"], l["line_type"]) for l in p["inv"]["lines"]]
    return inc(p["vendor_id"], p["invoice_date"], lines, dept=p["department_id"], requester=p["requester_user_id"],
               ship=p["ship_to_province"], number=kw.pop("number", p["vendor_invoice_number"]), **kw)


# 1-2 Coastline Cartage: bank account changed 2026-10-28 by email, never verified. The PDFs carry the new account.
inc("V004", D(2026, 10, 27), [("LTL shipment Vancouver-Calgary", 1, "1840.00", "services"),
                              ("LTL shipment Richmond-Edmonton", 1, "1295.50", "services"),
                              ("Fuel surcharge (9.40%)", 1, "294.74", "services")],
    dept="D01", requester="U016", ship="AB", file="scan_3317.png", scan=True, received=D(2026, 10, 29),
    issue="Vendor bank account changed 2026-10-28 by email request, not verified by callback (BC-009). The invoice's remit-to matches the NEW account already in the vendor master.",
    expect="Payment hold + bank-change fraud alert (star case). Scanned PNG.")
inc("V004", D(2026, 10, 30), [("LTL shipment Vancouver-Toronto", 1, "3120.00", "services"),
                              ("LTL shipment Richmond-Winnipeg", 1, "1488.25", "services"),
                              ("LTL shipment Vancouver-Saskatoon", 1, "960.00", "services"),
                              ("Fuel surcharge (10.20%)", 1, "567.96", "services")],
    dept="D01", requester="U013", ship="ON", received=D(2026, 10, 31),
    issue="Second Coastline bill after the unverified bank change.", expect="Payment hold + bank-change fraud alert.")
# 3 Lookalike vendor (V052) of Ashby & Crane LLP (V026)
inc("V052", D(2026, 10, 26), [("Legal services - Carrier agreement negotiation", "14.5", "495.00", "services"),
                              ("Disbursements and filing fees", 1, "212.40", "services")],
    dept="D08", requester="U040", ship="ON", lang="fr", received=D(2026, 10, 28),
    issue="'Ashby and Crane LLP' (V052) is a lookalike of 'Ashby & Crane LLP' (V026): different GST #, bank account and email domain (ashbyandcrane-llp.example vs ashbycrane.example). Email is in French.",
    expect="Lookalike vendor / sender alert; Legal adds the finance-manager step.")
# 4-6 Exact duplicates of paid history bills
copy_hist(hist("V001", lambda p: p["invoice_date"].month in (8, 9)), received=D(2026, 10, 22),
          issue="Invoice number already in history and paid (exact re-send).", expect="Duplicate (exact).")
copy_hist(hist("V026"), received=D(2026, 10, 24),
          issue="Invoice number already in history and paid.", expect="Duplicate (exact).")
copy_hist(hist("V015", lambda p: p["invoice_date"].month == 9), received=D(2026, 10, 23),
          issue="September subscription invoice re-sent; same number as the paid one.", expect="Duplicate (exact).")
# 7-8 Unknown vendors
inc(None, D(2026, 10, 21), [("Same-day courier - Toronto", 14, "38.50", "services"),
                            ("Next-day courier - Toronto-Ottawa", 6, "64.00", "services")],
    dept="D01", requester="U019", ship="ON", number="SB-1187", terms=30, received=D(2026, 10, 22),
    vendor_block=dict(name="Silver Birch Couriers", address="77 Signet Dr, Toronto ON M9L 1T9",
                      gst=f"{R.randint(100000000, 999999999)}RT0001", qst="", email="billing@silverbirchcouriers.example",
                      province="ON", country="CA"),
    remit={"institution": "004", "transit": "33120", "account": "5520091847"},
    issue="Vendor not in the vendor master.", expect="Unknown vendor: hold + onboarding.")
inc(None, D(2026, 10, 29), [("Data migration services", 1, "3850.00", "services"),
                            ("Database support retainer - November", 1, "1200.00", "services")],
    dept="D05", requester="U031", ship="BC", number="QR-20419", terms=30, received=D(2026, 10, 30),
    vendor_block=dict(name="Quantum Ridge Data Services Inc.", address="1090 Homer St, Vancouver BC V6B 2W9",
                      gst=f"{R.randint(100000000, 999999999)}RT0001", qst="", email="ar@quantumridge.example",
                      province="BC", country="CA"),
    remit={"institution": "010", "transit": "08812", "account": "448120733"},
    issue="Vendor not in the vendor master.", expect="Unknown vendor: hold + onboarding.")
# 9 Pending-review vendor with no GST number, against its open PO
maple_po = new_po("V054", "D06", "U036", D(2026, 10, 6), "3480.00", "open",
                  desc="Events & Catering - Maplewood Catering Co. (fall staff event)")
inc("V054", D(2026, 10, 24), [("Staff appreciation lunch (85 guests)", 85, "34.00", "services"),
                              ("Service staff (hours)", 12, "39.00", "services")],
    dept="D06", requester="U036", ship="BC", po="PENDING_PO:maple", received=D(2026, 10, 26),
    issue="Vendor status pending_review and no GST number on file; no tax charged.",
    expect="New/pending vendor adds the finance-manager step (P05); held until vendor approved.")
# 10 Quebec legal services charged GST only
inc("V027", D(2026, 10, 23), [("Legal services - Commercial lease review", "16", "455.00", "services")],
    dept="D04", requester="U030", ship="QC", lang="fr", received=D(2026, 10, 25),
    tax_override=lambda lt: ("GST5", ""),
    issue="Delivered to the Montreal office (QC) but only GST/TPS is charged; QST/TVQ missing.",
    expect="Tax mismatch vs ship-to province.")
# 11-13 Split invoices, Meridian, same day, each under $10k
for hrs in ("18.5", "19", "18.25"):
    inc("V029", D(2026, 10, 27), [("Consulting hours - Fleet routing study", hrs, "230.00", "services")],
        dept="D01", requester="U021", ship="ON", received=D(2026, 10, 28),
        issue="One of three Meridian invoices dated 2026-10-27 for the same project, each under the $10,000 approver limit.",
        expect="Split-invoice flag; route at the combined amount (adds Finance Manager).")
# 14-15 Credit notes
hand_src = hist("V043", lambda p: p["invoice_date"].month >= 6)
inc("V043", D(2026, 10, 25), [(f"Credit - {l['description']} (invoice {hand_src['vendor_invoice_number']})",
                               -l["quantity"], l["unit_price"], l["line_type"]) for l in hand_src["inv"]["lines"]],
    dept=hand_src["department_id"], requester=hand_src["requester_user_id"], ship=hand_src["ship_to_province"],
    number=f"CN-{hand_src['vendor_invoice_number']}", doc="credit_note", received=D(2026, 10, 27),
    issue=f"Credit note for the full amount of paid invoice {hand_src['vendor_invoice_number']} ({hand_src['invoice_id']}).",
    expect="Credit handling; credit equals a paid invoice (refund or offset).")
inc("V002", D(2026, 10, 28), [("Credit - damaged freight claim, shipment Toronto-Montreal", -1, "412.50", "services")],
    dept="D01", requester="U018", ship="QC", number=f"CN-{next_number('V002')}", doc="credit_note",
    received=D(2026, 10, 29), issue="Credit note (negative total) for a damaged-freight claim.",
    expect="Credit handling, net against vendor balance.")
# 16-17 USD SaaS
inc("V016", D(2026, 11, 1), [(FIXED["V016"][2], 1, FIXED["V016"][0], "services")], dept="D05", requester="U032",
    ship="ON", received=D(2026, 11, 1), issue="US vendor, USD, no Canadian tax.", expect="FX conversion + FOREIGN tax code.")
inc("V018", D(2026, 11, 1), [(FIXED["V018"][2], 1, FIXED["V018"][0], "services")], dept="D02", requester="U025",
    ship="ON", received=D(2026, 11, 2), issue="US vendor, USD, no Canadian tax.", expect="FX conversion + FOREIGN tax code.")
# 18-19 French invoices
inc("V005", D(2026, 10, 24), [("Expedition LTL Montreal-Toronto", 1, "1735.00", "services"),
                              ("Expedition LTL Montreal-Quebec City", 1, "880.40", "services"),
                              ("Supplement carburant (9,10 %)", 1, "238.00", "services")],
    dept="D01", requester="U018", ship="QC", lang="fr", received=D(2026, 10, 26),
    issue="French invoice: amounts printed as '1 234,56 $', TPS/TVQ labels.", expect="Normalisation to GST5 + QST9975.")
inc("V039", D(2026, 11, 1), [("Loyer de base - Montreal (novembre)", 1, "5500.00", "services"),
                             ("Frais d'exploitation - Montreal (novembre)", 1, "900.00", "services")],
    dept="D07", requester="U039", ship="QC", lang="fr", received=D(2026, 11, 1),
    issue="French rent invoice, '1 234,56 $' format, TPS/TVQ; Facilities manager on leave.",
    expect="Normalisation; approval goes to the delegate (Elise Tremblay) on behalf of Liam O'Connor.")
# 20 Fuel surcharge over cap
inc("V001", D(2026, 10, 26), [("LTL shipment Hamilton-Montreal", 1, "2140.00", "services"),
                              ("LTL shipment Hamilton-Ottawa", 1, "1365.00", "services"),
                              ("Fuel surcharge (21%)", 1, "736.05", "services")],
    dept="D01", requester="U014", ship="ON", received=D(2026, 10, 27),
    issue="Fuel surcharge 21% vs the 12% cap in contract C018.", expect="Contract fuel-surcharge cap exceeded.")
# 21 Fixed fee billed double
inc("V019", D(2026, 11, 1), [("Abacus Ledger accounting platform - 2 entities", 2, "1320.00", "services")],
    dept="D04", requester="U030", ship="AB", received=D(2026, 11, 2),
    issue="Billed $2,640.00 vs the $1,320.00 fixed monthly fee in the contract.", expect="Contract fixed-fee mismatch.")
# 22 Monthly cap exceeded
inc("V008", D(2026, 10, 31), [("Fleet fuel card charges - October", 1, "11420.55", "services")],
    dept="D01", requester="U016", ship="AB", received=D(2026, 11, 1),
    issue="$11,420.55 vs the $9,500 not-to-exceed monthly cap (fleet fuel card program).",
    expect="Contract spend limit exceeded; also over $10k so Finance Manager is added.")
# 23 Hourly rate over cap
inc("V028", D(2026, 10, 22), [("Quarterly review engagement - Q3", 1, "4200.00", "services"),
                              ("Tax advisory hours", 12, "425.00", "services")],
    dept="D04", requester="U030", ship="ON", received=D(2026, 10, 23),
    issue="Tax advisory billed at $425/h vs the $380/h cap in the retainer.", expect="Contract rate cap exceeded.")
# 24 HST charged on an Alberta delivery
inc("V006", D(2026, 10, 25), [("LTL shipment Calgary-Edmonton", 1, "940.00", "services"),
                              ("LTL shipment Calgary-Red Deer", 1, "615.00", "services")],
    dept="D01", requester="U017", ship="AB", tax_override=lambda lt: ("HST13", ""), received=D(2026, 10, 27),
    issue="Ontario HST 13% charged on a delivery to the Calgary warehouse (AB should be GST 5%).",
    expect="Tax mismatch vs ship-to province.")
# 25 BC PST on services
inc("V031", D(2026, 10, 24), [("Creative design services - fall catalogue", 1, "3600.00", "services"),
                              ("Digital ad campaign - Holiday campaign", 1, "2250.00", "services")],
    dept="D03", requester="U028", ship="BC", tax_override=lambda lt: ("GST5", "PST7_BC"), received=D(2026, 10, 26),
    issue="BC PST 7% charged on services (PST applies to goods only).", expect="Tax mismatch: PST on services.")
# 26 Arithmetic: 18 x 225.00 printed as 4,250.00
inc("V032", D(2026, 10, 23), [("Digital ad campaign - Search ads (weeks)", 18, "225.00", "services"),
                              ("Creative design services", 1, "1150.00", "services")],
    dept="D03", requester="U027", ship="ON", overrides={"line_amounts": {1: "4250.00"}}, received=D(2026, 10, 24),
    issue="Line 1 prints 18 x $225.00 as $4,250.00 (should be $4,050.00); subtotal and tax follow the wrong figure.",
    expect="Arithmetic error flag.")
# 27 Total $100 high
inc("V041", D(2026, 10, 17), [("Energy - October", 1, "2684.30", "services"),
                              ("Delivery and regulatory charges", 1, "311.85", "services")],
    dept="D07", requester="U038", ship="BC", overrides={"total_delta": "100.00"}, received=D(2026, 10, 21),
    issue="Printed total is $100.00 more than subtotal + GST.", expect="Arithmetic (total) mismatch.")
# 28 Future-dated
crest_po = new_po("V011", "D01", "U020", D(2026, 10, 14), "4600.00", "open")
inc("V011", D(2026, 11, 18), [("Stretch wrap cases", 40, "61.50", "goods"), ("Corner boards (bundle)", 30, "29.75", "goods")],
    dept="D01", requester="U020", ship="ON", po="PENDING_PO:crest", received=D(2026, 11, 2),
    issue="Invoice dated 2026-11-18, after it was received (2026-11-02).", expect="Future-dated invoice flag.")
# 29 Expired contract
gear_po = new_po("V044", "D01", "U016", D(2026, 10, 9), "3900.00", "open")
inc("V044", D(2026, 10, 22), [("Dock leveler repair", 1, "2280.00", "services"), ("Forklift repair parts", 4, "312.50", "goods")],
    dept="D01", requester="U016", ship="AB", po="PENDING_PO:gear", received=D(2026, 10, 23),
    note="Footer: 'Billed under maintenance services agreement C025'.",
    issue="Billed under maintenance agreement C025, which expired 2026-06-30.", expect="Expired-contract flag.")
# 30 PO over remaining balance
oak_po = new_po("V010", "D01", "U013", D(2026, 10, 8), "1200.00", "open")
inc("V010", D(2026, 10, 24), [("Pallets (48x40), qty 280", 280, "9.25", "goods"), ("Stretch wrap cases", 6, "58.00", "goods")],
    dept="D01", requester="U013", ship="ON", po="PENDING_PO:oak", received=D(2026, 10, 24),
    issue="PO remaining balance is $1,200.00; the invoice total is far higher.",
    expect="Over PO balance: goes to the finance manager (P12).")
# 31 PO-required vendor, no PO
inc("V013", D(2026, 10, 21), [("Safety boots", 14, "158.00", "goods"), ("Hi-vis vests", 40, "18.50", "goods")],
    dept="D01", requester="U017", ship="AB", received=D(2026, 10, 22),
    issue="Sentinel requires a PO; none on the invoice.", expect="Missing PO: hold (P13).")
# 32 Closed PO referenced
steel_closed = [p for p in PLANS if p["vendor_id"] == "V014" and p.get("po")][-1]["po"]
inc("V014", D(2026, 10, 28), [("Hard hats", 24, "29.00", "goods"), ("Safety glasses (box of 12)", 10, "61.00", "goods")],
    dept="D01", requester="U014", ship="ON", po="CLOSED_PO", received=D(2026, 10, 29),
    issue="References a PO that is already closed (fully invoiced).", expect="Closed PO flag.")
# 33 Already paid by employee card
ink = inc("V024", D(2026, 10, 13), [("Toner cartridges", 8, "128.00", "goods"), ("Printer paper cartons", 12, "47.50", "goods")],
          dept="D06", requester="U036", ship="BC", received=D(2026, 10, 30),
          issue="Olivia Singh already paid this exact amount on her Brim card on 2026-10-15 (INKWELL OFFICE SUPPLY).",
          expect="Already paid by card (duplicate payment risk).")
emp_card("U036", D(2026, 10, 15), "INKWELL OFFICE SUPPLY", "5943", ink["total"], receipt="yes")
# 34 Duplicate vendor record on hold
inc("V053", D(2026, 10, 25), [("LTL shipment Mississauga-Montreal", 1, "1520.00", "services"),
                              ("Fuel surcharge (9.00%)", 1, "136.80", "services")],
    dept="D01", requester="U019", ship="ON", received=D(2026, 10, 27),
    issue="Billed by V053, a duplicate record of Bayshore Transport Inc. (V002) that is on_hold; same bank account.",
    expect="Vendor on hold / duplicate vendor record.")
# 35 Over $50k: controller step
anchor_po = new_po("V012", "D01", "U021", D(2026, 9, 29), "72000.00", "open",
                   desc="Warehouse & Packaging - Anchor Racking Systems (Calgary racking expansion)")
inc("V012", D(2026, 10, 27), [("Selective pallet racking - uprights and beams", 1, "48600.00", "goods"),
                              ("Installation labour", 1, "9900.00", "services"),
                              ("Seismic anchoring and engineering stamp", 1, "3300.00", "services")],
    dept="D01", requester="U021", ship="AB", po="PENDING_PO:anchor", received=D(2026, 10, 28),
    issue="$64,890 total: over $50k.", expect="Approval adds the Controller (P03).")
# 36 Over $250k: CFO step
work_po = new_po("V025", "D07", "U038", D(2026, 9, 18), "256000.00", "open",
                 desc="Office Furniture - Workform Furniture Ltd. (Calgary office fit-out)")
inc("V025", D(2026, 10, 30), [("Workstations - Calgary fit-out", 96, "1480.00", "goods"),
                              ("Ergonomic task chairs", 96, "640.00", "goods"),
                              ("Meeting room furniture packages", 6, "4150.00", "goods"),
                              ("Delivery and installation", 1, "13920.00", "services")],
    dept="D07", requester="U038", ship="AB", po="PENDING_PO:work", received=D(2026, 10, 31),
    issue="Over $250k CAD; Facilities manager on leave.", expect="Approval adds Finance Manager, Controller and CFO (P04); delegate for Liam.")
# 37 Bank details on the invoice differ from the vendor master
inc("V045", D(2026, 10, 20), [(FIXED["V045"][2] + " - October", 1, FIXED["V045"][0], "services")],
    dept="D07", requester="U037", ship="ON", received=D(2026, 10, 20),
    remit={"institution": "016", "transit": "41870", "account": "820417736"},
    body="Hello,\n\nPlease note our banking details have changed. Kindly remit invoice {n} to the new account shown on the invoice and update your records.\n\nThank you,\nSparkle Pro Janitorial Accounts Receivable",
    issue="Remit-to bank account on the invoice differs from the vendor master; the email asks AP to 'update your records'.",
    expect="Bank-details mismatch fraud alert; payment hold until verified.")
# 38 Near-duplicate: reformatted number, same amount
rid = hist("V007", lambda p: p["invoice_date"].month == 9)
copy_hist(rid, received=D(2026, 10, 26), number=rid["vendor_invoice_number"] + "-R",
          issue=f"Re-issue of paid {rid['vendor_invoice_number']} with '-R' suffix, same amount and date.",
          expect="Possible duplicate (near-exact).")
# 39 Pending-review vendor, ordinary bill
inc("V055", D(2026, 10, 29), [("Warehouse safety audit - Calgary", 1, "3450.00", "services"),
                              ("Audit report and recommendations", 1, "850.00", "services")],
    dept="D01", requester="U016", ship="AB", received=D(2026, 10, 30),
    issue="Vendor status pending_review.", expect="New-vendor approval step (P05); held until approved.")
# --- clean bills (40-56) ---
for vid, dept, req in (("V036", "D07", "U037"), ("V037", "D07", "U038"), ("V038", "D07", "U039")):
    fee, _, desc = FIXED[vid]
    inc(vid, D(2026, 11, 1), [(desc[0], 1, desc[1], "services"), (desc[2], 1, desc[3], "services")], dept=dept,
        requester=req, ship=OFFICE_PROV[vid], received=D(2026, 11, 1),
        issue="Clean November rent.", expect="Routine; Facilities approval goes to the delegate (Liam on leave)." +
        (" Over $10k adds the Finance Manager." if vid in ("V036", "V038") else ""))
for vid, req in (("V017", "U033"), ("V020", "U030"), ("V022", "U032")):
    fee, _, desc = FIXED[vid]
    inc(vid, D(2026, 11, 1) if vid != "V022" else D(2026, 10, 28), [(desc, 1, fee, "services")], dept=FIXED_DEPT[vid],
        requester=req, ship=USERS[req]["work_province"] if vid != "V022" else "ON",
        received=D(2026, 11, 2) if vid != "V022" else D(2026, 10, 29), issue="Clean contract bill.", expect="Routine.")
inc("V040", D(2026, 10, 15), [("Electricity - October", 1, "2318.44", "services")], dept="D07", requester="U039",
    ship="ON", received=D(2026, 10, 20), issue="Clean utility bill.", expect="Routine.")
inc("V042", D(2026, 10, 12), [("Energy - October", 1, "2905.10", "services"), ("Delivery and regulatory charges", 1, "264.35", "services")],
    dept="D07", requester="U039", ship="AB", received=D(2026, 10, 21), issue="Clean utility bill.", expect="Routine.")
inc("V003", D(2026, 10, 22), [("LTL shipment Winnipeg-Regina", 1, "1185.00", "services"),
                              ("LTL shipment Winnipeg-Toronto", 1, "2210.00", "services")],
    dept="D01", requester="U021", ship="ON", file="scan_5520.png", scan=True, received=D(2026, 10, 24),
    issue="Clean bill, scanned (PNG).", expect="Routine; extraction from a scan.")
inc("V001", D(2026, 10, 20), [("LTL shipment Hamilton-Calgary", 1, "2460.00", "services"),
                              ("LTL shipment Hamilton-Winnipeg", 1, "1310.00", "services"),
                              ("Fuel surcharge (10.50%)", 1, "395.85", "services")],
    dept="D01", requester="U013", ship="ON", received=D(2026, 10, 21), issue="Clean freight bill.", expect="Routine.")
inc("V030", D(2026, 10, 28), [("Consulting hours - Pricing analysis", 22, "240.00", "services"),
                              ("Workshop facilitation", 1, "1350.00", "services")],
    dept="D02", requester="U025", ship="ON", received=D(2026, 10, 30), issue="Clean consulting bill.", expect="Routine.")
inc("V048", D(2026, 10, 26), [("Placement fee - Senior Accountant", 1, "11850.00", "services")], dept="D06",
    requester="U035", ship="ON", received=D(2026, 10, 27), issue="Clean, over $10k.",
    expect="Routine; adds the Finance Manager (P02).")
inc("V049", D(2026, 11, 1), [(FIXED["V049"][2], 1, FIXED["V049"][0], "services")], dept="D06", requester="U035",
    ship="ON", received=D(2026, 11, 1), issue="Clean contract bill.", expect="Routine.")
inc("V033", D(2026, 10, 22), [("Printed brochures", 2500, "0.48", "goods"), ("Trade show banners", 3, "265.00", "goods")],
    dept="D03", requester="U029", ship="AB", received=D(2026, 10, 22),
    issue="Clean bill; 2/10 net 30 discount available until 2026-11-01.", expect="Routine; early-pay discount.")
cedar_po = new_po("V047", "D03", "U027", D(2026, 10, 2), "5200.00", "open")
inc("V047", D(2026, 10, 24), [("Team event catering", 1, "2860.00", "services"), ("Venue rental", 1, "1450.00", "services")],
    dept="D03", requester="U027", ship="QC", po="PENDING_PO:cedar", received=D(2026, 10, 25),
    issue="Clean bill against an open PO.", expect="Routine; PO match.")
inc("V009", D(2026, 10, 21), [("Tire service (units)", 12, "336.00", "goods"), ("Wheel alignment", 4, "149.00", "services")],
    dept="D01", requester="U014", ship="ON", received=D(2026, 10, 22), issue="Clean bill.", expect="Routine.")
inc("V050", D(2026, 10, 30), [("Compliance training licences", 32, "54.00", "services")], dept="D06",
    requester="U035", ship="ON", received=D(2026, 10, 31), issue="US vendor, USD.", expect="FX conversion, FOREIGN.")
assert len(INCOMING) == 56, len(INCOMING)

# Resolve the PO references now that POs have ids.
pad_po = TARGET["purchase_orders"] - len(POS)
assert pad_po >= 0, len(POS)
for _ in range(pad_po):  # open POs for upcoming work, not yet invoiced
    vid = R.choice(["V010", "V011", "V013", "V014", "V043", "V047"])
    dept = "D07" if vid == "V043" and R.random() < 0.5 else ("D03" if vid == "V047" else "D01")
    new_po(vid, dept, pick_requester(dept, D(2026, 10, 20)), rdate(D(2026, 10, 1), D(2026, 10, 30)),
           R.uniform(900, 6500), "open")
POS.sort(key=lambda p: (p["issue_date"], p["vendor_id"]))
for i, p in enumerate(POS, start=1):
    p["po_id"] = f"PO-2026-{i:04d}"
oak_po["status"] = "partially_invoiced"
PO_REF = {"maple": maple_po, "crest": crest_po, "gear": gear_po, "oak": oak_po, "anchor": anchor_po,
          "work": work_po, "cedar": cedar_po}
for rec in INCOMING:
    if rec["po_number"] == "CLOSED_PO":
        rec["po_number"] = steel_closed["po_id"]
    elif rec["po_number"] and rec["po_number"].startswith("PENDING_PO:"):
        rec["po_number"] = PO_REF[rec["po_number"].split(":")[1]]["po_id"]
for p in PLANS:
    p["po_id"] = p["po"]["po_id"] if p.get("po") else ""
    if "copy_of" in p and p["copy_of"].get("po"):
        p["po_id"] = p["copy_of"]["po"]["po_id"]

# ----------------------------------------------------------------------------------------------------------------
# File names and emails
# ----------------------------------------------------------------------------------------------------------------


def slug(name):
    return "".join(ch for ch in name.lower() if ch.isalnum())[:16]


for n, rec in enumerate(INCOMING, start=1):
    if rec["file"]:
        continue
    num = rec["invoice_number"]
    style = n % 3
    vn = rec["vendor"]["name"]
    if style == 0:
        rec["file"] = f"Invoice {num}.pdf"
    elif style == 1:
        rec["file"] = f"{slug(vn)}_{num.replace('-', '')}.pdf"
    else:
        rec["file"] = f"{slug(vn)}-inv-{n:03d}.pdf"
assert len({r["file"] for r in INCOMING}) == 56

BODIES = {
    "en1": "Hello,\n\nPlease find attached our invoice {n} for services provided. Payment terms are on the invoice.\n\nThank you,\n{v} Accounts Receivable",
    "en2": "Good afternoon,\n\nAttached is invoice {n}. Kindly remit by the due date.\n\n{v} Billing",
    "en3": "Hi AP team,\n\nInvoice {n} attached. Let us know if you have any questions.\n\nRegards,\n{v}",
    "fr": "Bonjour,\n\nVeuillez trouver ci-joint la facture {n}.\n\nCordialement,\n{v}",
    "cn": "Hello,\n\nPlease find attached credit note {n}. Please apply it against our open balance.\n\nThank you,\n{v} Accounts Receivable",
}
SUBJECTS = ["Invoice {n}", "Payment due: invoice {n}", "Invoice {n} from {v}", "{v} - Invoice {n} attached", "Facture {n}"]

EMAILS = []
meridian = [r for r in INCOMING if r["vendor_id"] == "V029"]
for rec in INCOMING:
    if rec is meridian[2]:
        continue  # sent together with meridian[1]
    vn = rec["vendor"]["name"]
    num = rec["invoice_number"]
    if rec["body"]:
        body = rec["body"].format(n=num)
    elif rec["document_type"] == "credit_note":
        body = BODIES["cn"].format(n=num, v=vn)
    elif rec["language"] == "fr" or R.random() < 0.15:
        body = BODIES["fr"].format(n=num, v=vn)
    else:
        body = BODIES[R.choice(["en1", "en2", "en3"])].format(n=num, v=vn)
    if rec["document_type"] == "credit_note":
        subject = f"Credit note {num}"
    else:
        subject = R.choice(SUBJECTS).format(n=num, v=vn)
    files = [rec["file"]]
    if rec is meridian[1]:
        files.append(meridian[2]["file"])
        subject = f"Invoices {num} and {meridian[2]['invoice_number']}"
        body = f"Hi AP team,\n\nInvoices {num} and {meridian[2]['invoice_number']} attached. Let us know if you have any questions.\n\nRegards,\n{vn}"
    recv = rec["received"] or add(rec["invoice_date"], 1)
    t = at(recv, 7, 18)
    EMAILS.append(dict(from_name=f"{vn} Accounts Receivable", from_address=rec["vendor"]["email"],
                       to_address="ap@harborline-logistics.example", subject=subject,
                       received_at=t.strftime("%Y-%m-%dT%H:%M:00"), body=body, attachments=files, _rec=rec))
    rec["email_subject"] = subject

inq = hist("V013")
NOATT = [
    dict(from_name="Waypoint Corporate Travel", from_address="ar@waypointtravel.example", subject="Invoice attached",
         received_at="2026-10-29T15:12:00",
         body="Hello,\n\nPlease find our October travel invoice attached.\n\nWaypoint Corporate Travel", attachments=[]),
    dict(from_name="Sentinel Safety Supply", from_address="ar@sentinelsafety.example",
         subject=f"Where is payment for invoice {inq['vendor_invoice_number']}?", received_at="2026-10-30T10:41:00",
         body=f"Hi,\n\nWe have not seen payment for invoice {inq['vendor_invoice_number']} dated {inq['invoice_date']}. Could you confirm the status?\n\nThanks,\nSentinel Accounts",
         attachments=[]),
    dict(from_name="Workform Furniture", from_address="news@workformfurniture.example",
         subject="Winter clearance: up to 25% off task chairs", received_at="2026-10-31T06:55:00",
         body="Our winter clearance is on. Save up to 25% on task chairs, sit/stand desks and storage.", attachments=[]),
    dict(from_name="Foothills Courier Co. Accounts Receivable", from_address="ar@foothillscourier.example",
         subject="Statement of account - October 2026", received_at="2026-11-02T08:47:00",
         body="Hello,\n\nAttached is your statement of account for October. No action needed unless you see differences.\n\nFoothills Courier Co.",
         attachments=[]),
]
EMAILS.extend(NOATT)
assert len(EMAILS) == 59, len(EMAILS)
# Ids follow arrival order, with a little shuffle (the given inbox is not strictly in id order either).
EMAILS.sort(key=lambda e: e["received_at"])
ids = list(range(1, 60))
for i in range(0, 58, 7):
    ids[i], ids[i + 1] = ids[i + 1], ids[i]
for e, i in zip(EMAILS, ids):
    e["email_id"] = f"EM-{i:03d}"
    if e.get("_rec"):
        for r in (e["_rec"],) + ((meridian[2],) if e["_rec"] is meridian[1] else ()):
            r["email_id"] = e["email_id"]
            r["received_at"] = e["received_at"]
            r["email_subject"] = e["subject"]

# ----------------------------------------------------------------------------------------------------------------
# Write dataset_demo/
# ----------------------------------------------------------------------------------------------------------------


def s(x):
    if x is None:
        return ""
    if isinstance(x, Decimal):
        return f"{x:.2f}"
    if isinstance(x, (dt.date, dt.datetime)):
        return x.isoformat()
    return str(x)


def write(name, cols, rows, fmt=None):
    path = OUT / "data" / f"{name}.csv"
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(cols)
        for r in rows:
            w.writerow([(fmt or {}).get(c, s)(r.get(c)) for c in cols])
    return len(rows)


if OUT.exists():
    for sub in ("data", "inbox"):
        shutil.rmtree(OUT / sub, ignore_errors=True)
(OUT / "data").mkdir(parents=True, exist_ok=True)
(OUT / "inbox").mkdir(parents=True, exist_ok=True)
(OUT / "incoming_invoices").mkdir(parents=True, exist_ok=True)
for name in ("approval_policies", "departments", "gl_accounts", "tax_codes", "users"):
    shutil.copyfile(SRC / "data" / f"{name}.csv", OUT / "data" / f"{name}.csv")

counts = {}
counts["vendors"] = write("vendors", ["vendor_id", "legal_name", "category", "default_gl_account", "address",
                                      "province_or_state", "country", "gst_hst_number", "qst_number",
                                      "payment_terms_days", "early_pay_discount", "preferred_payment_method",
                                      "accepts_card_payment", "bank_institution", "bank_transit", "bank_account",
                                      "bank_details_updated_at", "ap_contact_email", "requires_po", "status",
                                      "onboarded_at"], list(VENDORS.values()))
counts["contracts"] = write("contracts", list(CONTRACTS[0].keys()), CONTRACTS)
counts["purchase_orders"] = write("purchase_orders", ["po_id", "vendor_id", "department_id", "requester_user_id",
                                                      "issue_date", "description", "total_amount", "currency",
                                                      "status", "approved_by"], POS)
counts["fx_rates"] = write("fx_rates", ["date", "usd_to_cad"], [dict(date=k, usd_to_cad=f"{v}") for k, v in FX.items()])
counts["bank_change_history"] = write("bank_change_history", ["change_id", "vendor_id", "changed_at",
                                                              "changed_by_user_id", "request_channel",
                                                              "verified_by_callback", "old_account_last4",
                                                              "new_account_last4"], BC)

inv_rows, line_rows = [], []
for p in PLANS:
    inv = p["inv"]
    vid = p["vendor_id"]
    contract = CONTRACT_OF.get(vid, "")
    cobj = next((c for c in CONTRACTS if c["contract_id"] == contract), None)
    if cobj and not (cobj["start_date"] <= p["invoice_date"].isoformat() <= cobj["end_date"]):
        contract = ""
    inv_rows.append(dict(invoice_id=p["invoice_id"], vendor_id=vid, vendor_invoice_number=p["vendor_invoice_number"],
                         invoice_date=p["invoice_date"], received_date=p["received_date"], due_date=p["due_date"],
                         currency=inv["currency"], subtotal=inv["subtotal"], gst_hst_amount=inv["gst"],
                         pst_qst_amount=inv["pst"], total=inv["total"],
                         fx_rate_to_cad=("1" if inv["currency"] == "CAD" else f"{inv['fx']}"),
                         total_cad=inv["total_cad"], department_id=p["department_id"],
                         requester_user_id=p["requester_user_id"], po_id=p["po_id"], contract_id=contract,
                         ship_to_province=p["ship_to_province"], status=p["status"]))
    for l in inv["lines"]:
        q_ = l["quantity"]
        line_rows.append(dict(l, invoice_id=p["invoice_id"],
                              quantity=(f"{q_.normalize():f}" if q_ == q_.to_integral() else f"{q_}"),
                              unit_price=l["unit_price"], amount=l["amount"]))
counts["invoices"] = write("invoices", ["invoice_id", "vendor_id", "vendor_invoice_number", "invoice_date",
                                        "received_date", "due_date", "currency", "subtotal", "gst_hst_amount",
                                        "pst_qst_amount", "total", "fx_rate_to_cad", "total_cad", "department_id",
                                        "requester_user_id", "po_id", "contract_id", "ship_to_province", "status"],
                           inv_rows)
counts["invoice_lines"] = write("invoice_lines", ["invoice_id", "line_no", "description", "quantity", "unit_price",
                                                  "amount", "gl_account", "department_id", "gst_hst_code",
                                                  "pst_qst_code", "line_type"], line_rows)
counts["approvals"] = write("approvals", ["approval_id", "invoice_id", "step_no", "approver_user_id", "approver_role",
                                          "action", "acted_at", "comment"], APPROVALS)
for r in PAYMENTS:
    r["fx_rate_to_cad"] = "1" if r["currency"] == "CAD" else f"{r['fx_rate_to_cad']}"
counts["payments"] = write("payments", ["payment_id", "invoice_id", "vendor_id", "method", "scheduled_date", "paid_date",
                                        "currency", "amount", "fx_rate_to_cad", "amount_cad", "discount_taken",
                                        "reference", "status", "prepared_by", "released_by"], PAYMENTS)
counts["bank_statement"] = write("bank_statement", ["bank_txn_id", "posted_date", "description", "debit", "credit"], STMT)
CARDS.sort(key=lambda c: (c["posted_date"], c["source"] == "ap_virtual_card", c["card_id"]))
for i, c in enumerate(CARDS, start=1):
    c["txn_id"] = f"CT-{i:05d}"
counts["card_transactions"] = write("card_transactions", ["txn_id", "card_id", "cardholder_user_id", "posted_date",
                                                          "merchant_descriptor", "mcc", "amount", "currency",
                                                          "amount_cad", "source", "receipt_attached", "memo"], CARDS)

email_out = []
for e in EMAILS:
    if e["attachments"]:
        email_out.append({k: e[k] for k in ("email_id", "from_name", "from_address", "to_address", "subject",
                                             "received_at", "body", "attachments")})
    else:  # the no-attachment emails use a different key order in the given inbox too
        email_out.append({k: e[k] for k in ("from_name", "from_address", "subject", "received_at", "body",
                                             "attachments", "email_id")} | {"to_address": "ap@harborline-logistics.example"})
with open(OUT / "inbox" / "emails.json", "w", encoding="utf-8") as f:
    json.dump(email_out, f, indent=2, ensure_ascii=False)
    f.write("\n")

readme = (SRC / "README.md").read_text(encoding="utf-8")
readme = readme.replace("# Harborline Logistics AP dataset (synthetic)",
                        "# Harborline Logistics AP dataset - demo set (synthetic)")
readme = readme.replace("Everything here is fictional",
                        "A second dataset in the same format as `dataset_given`, with different suppliers, history "
                        "and inbox. Everything here is fictional")
(OUT / "README.md").write_text(readme, encoding="utf-8")

for k, v in TARGET.items():
    assert counts[k] == v, (k, counts[k], v)

# ----------------------------------------------------------------------------------------------------------------
# Write dataset_demo_spec/ (what each incoming file must contain)
# ----------------------------------------------------------------------------------------------------------------
SPEC.mkdir(exist_ok=True)
TAXLABEL = {"HST13": ("HST 13%", "TVH 13 %"), "GST5": ("GST 5%", "TPS 5 %"), "QST9975": ("QST 9.975%", "TVQ 9,975 %"),
            "PST7_BC": ("PST 7%", "TVP 7 %")}


def jd(x):
    return f"{x:.2f}" if isinstance(x, Decimal) else x


spec_rows, gt = [], {}
for rec in sorted(INCOMING, key=lambda r: r["received_at"]):
    v = rec["vendor"]
    item = dict(
        file=rec["file"], email_id=rec["email_id"], email_subject=rec["email_subject"], received_at=rec["received_at"],
        format="PNG scan" if rec["scan"] else "PDF", language=rec["language"], document_type=rec["document_type"],
        vendor_id=rec["vendor_id"], vendor=v,
        bill_to=dict(name="Harborline Logistics Inc.", gst=HARBORLINE_GST, address=rec["ship_to"][1]),
        ship_to=dict(name=rec["ship_to"][0], address=rec["ship_to"][1], province=rec["ship_to_province"]),
        invoice_number=rec["invoice_number"], invoice_date=rec["invoice_date"].isoformat(),
        due_date=rec["due_date"].isoformat(), terms=("Due on receipt" if rec["terms_days"] == 0 else f"Net {rec['terms_days']}")
        + (" (2/10 net 30)" if rec["vendor_id"] and VENDORS[rec["vendor_id"]]["early_pay_discount"] else ""),
        currency=rec["currency"], po_number=rec["po_number"],
        lines=[dict(line_no=l["line_no"], description=l["description"], quantity=f"{l['quantity'].normalize():f}",
                    unit_price=jd(l["unit_price"]), amount=jd(l.get("printed_amount", l["amount"])),
                    **({"correct_amount": jd(l["amount"])} if "printed_amount" in l else {}))
               for l in rec["lines"]],
        subtotal=jd(rec["subtotal"]),
        taxes=[dict(code=c, label=TAXLABEL[c][1 if rec["language"] == "fr" else 0], amount=jd(a))
               for c, a in rec["taxes"].items()],
        total=jd(rec["total"]),
        **({"correct_total": jd(rec["correct_total"])} if rec["total"] != rec["correct_total"] else {}),
        remit_to=rec["remit"], requester_hint=dict(user_id=rec["requester_user_id"],
                                                    name=USERS[rec["requester_user_id"]]["full_name"],
                                                    department_id=rec["department_id"]),
        notes=rec["note"], planted_issue=rec["issue"], expected=rec["expect"])
    if rec["currency"] == "USD":
        item["fx_rate_to_cad_on_invoice_date"] = f"{rec['fx_rate_to_cad']}"
    spec_rows.append(item)
    gt[rec["file"]] = dict(vendorName=v["name"], gstNumber=v["gst"] or None, invoiceNumber=rec["invoice_number"],
                           invoiceDate=rec["invoice_date"].isoformat(), dueDate=rec["due_date"].isoformat(),
                           currency=rec["currency"], subtotal=jd(rec["subtotal"]), total=jd(rec["total"]),
                           taxes={c: jd(a) for c, a in rec["taxes"].items()}, lineCount=len(rec["lines"]),
                           remitAccount=(rec["remit"] or {}).get("account"), shipToProvince=rec["ship_to_province"],
                           poNumber=rec["po_number"], documentType=rec["document_type"], vendorId=rec["vendor_id"])
with open(SPEC / "incoming_invoices.json", "w", encoding="utf-8") as f:
    json.dump(spec_rows, f, indent=1, ensure_ascii=False, default=str)
    f.write("\n")
with open(SPEC / "ground_truth.json", "w", encoding="utf-8") as f:
    json.dump(dict(sorted(gt.items())), f, indent=1, ensure_ascii=False)
    f.write("\n")


def money_txt(x, lang, cur):
    x = Decimal(x)
    if lang == "fr":
        neg = "-" if x < 0 else ""
        whole, frac = f"{abs(x):,.2f}".split(".")
        return f"{neg}{whole.replace(',', ' ')},{frac} $" + (" US" if cur == "USD" else "")
    return ("-" if x < 0 else "") + ("US$" if cur == "USD" else "$") + f"{abs(x):,.2f}"


md = ["# dataset_demo: incoming invoice files to create", "",
      "Generated by `scripts/generate_demo_dataset.py`. `dataset_demo/inbox/emails.json` references these 56 files; "
      "put them in `dataset_demo/incoming_invoices/` with exactly these names. Each section gives everything the "
      "document must show. `incoming_invoices.json` has the same content in machine-readable form, and "
      "`ground_truth.json` is the extraction answer key in the format of `scripts/ground_truth.json`.", "",
      "Bill to: Harborline Logistics Inc., GST/HST 100000001RT0001, at the office named under Ship to below. The "
      "app reads the place of supply (ship-to province) from the bill-to address, as in dataset_given.", "",
      "French documents print amounts as `1 234,56 $` and label taxes TPS/TVQ/TVH. Amounts below are given in that "
      "format for French files.", "",
      "## Answer key", "", "| # | File | Vendor | Planted issue | Should trigger |", "| --- | --- | --- | --- | --- |"]
for i, it in enumerate(spec_rows, start=1):
    md.append(f"| {i} | `{it['file']}` | {it['vendor']['name']} | {it['planted_issue']} | {it['expected']} |")
md += ["", "Emails with no attachment (route to Needs attention): an \"Invoice attached\" with nothing attached "
       "(Waypoint), a payment inquiry (Sentinel), a supplier newsletter (Workform) and a statement of account "
       "(Foothills).", "", "## Documents", ""]
for i, it in enumerate(spec_rows, start=1):
    lang, cur = it["language"], it["currency"]
    v = it["vendor"]
    md.append(f"### {i}. `{it['file']}` ({it['format']}, {'French' if lang == 'fr' else 'English'}"
              f"{', CREDIT NOTE' if it['document_type'] == 'credit_note' else ''})")
    md.append("")
    md.append(f"- **From:** {v['name']}, {v['address']}  ")
    md.append(f"  GST/HST: {v['gst'] or '(none printed)'}" + (f" · QST: {v['qst']}" if v.get("qst") else "") +
              f" · {v['email']}")
    md.append(f"- **Ship to:** {it['ship_to']['name']}, {it['ship_to']['address']}")
    md.append(f"- **{'Credit note' if it['document_type'] == 'credit_note' else 'Invoice'} #:** {it['invoice_number']} · "
              f"**Date:** {it['invoice_date']} · **Due:** {it['due_date']} ({it['terms']}) · **Currency:** {cur}"
              + (f" · **PO:** {it['po_number']}" if it["po_number"] else ""))
    if it["remit_to"]:
        r = it["remit_to"]
        md.append(f"- **Remit to (EFT):** institution {r['institution']}, transit {r['transit']}, account {r['account']}")
    if it["notes"]:
        md.append(f"- **Also print:** {it['notes']}")
    md.append("")
    md.append("| # | Description | Qty | Unit price | Amount |")
    md.append("| --- | --- | --- | --- | --- |")
    for l in it["lines"]:
        md.append(f"| {l['line_no']} | {l['description']} | {l['quantity']} | {money_txt(l['unit_price'], lang, cur)} | "
                  f"{money_txt(l['amount'], lang, cur)} |")
    md.append(f"| | **Subtotal** | | | {money_txt(it['subtotal'], lang, cur)} |")
    for t in it["taxes"]:
        md.append(f"| | {t['label']} | | | {money_txt(t['amount'], lang, cur)} |")
    md.append(f"| | **Total** | | | **{money_txt(it['total'], lang, cur)}** |")
    md.append("")
    md.append(f"Planted issue: {it['planted_issue']}")
    md.append("")
(SPEC / "INCOMING_INVOICES.md").write_text("\n".join(md) + "\n", encoding="utf-8")

print("dataset_demo written:", ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
print("emails:", len(email_out), "| incoming files specified:", len(spec_rows))
