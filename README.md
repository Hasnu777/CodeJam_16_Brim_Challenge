# Brim AP Automation — Harborline Logistics

Inbox-to-payment accounts payable automation for the Brim sponsor challenge (McGill CodeJam 2026).

## Demo
_Video link, screenshots, demo logins per role_

## Run it
```bash
cp .env.example .env   # add LLM_API_KEY
docker compose up
# frontend: http://localhost:5173
```

## How it works
_Pipeline diagram + one paragraph per stage (intake → extraction → coding → validation → approval → payment → audit)_

## What we detect
_Table of flag types with example invoices from the dataset_

## Design decisions
See [docs/decisions.md](docs/decisions.md).

## Scaling to 100×
_Queue for extraction, indexes for duplicate search, audit partitioning…_

## Team
