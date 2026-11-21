#!/usr/bin/env bash
# Wipe every table and reload the dataset, then print row counts vs the dataset README.
# Usage: scripts/reset-and-seed.sh [backend-url]   (default http://localhost:8088)
set -euo pipefail
API="${1:-http://localhost:8088}"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT
# Reset is Admin-only: log in as Marcus Webb (U002, Controller) through the demo switcher.
curl -sf -c "$JAR" -H 'Content-Type: application/json' -d '{"userId":"U002"}' "$API/api/auth/login" > /dev/null || true
curl -sf -b "$JAR" -X POST "$API/api/admin/reset" > /dev/null
curl -sf -b "$JAR" "$API/api/admin/seed-report" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for k, v in d["tables"].items():
    print("%-22s %5d / %-5d %s" % (k, v["actual"], v["expected"], "ok" if v["match"] else "MISMATCH"))
print("ALL MATCH" if d["allMatch"] else "MISMATCHES FOUND")
sys.exit(0 if d["allMatch"] else 1)'
