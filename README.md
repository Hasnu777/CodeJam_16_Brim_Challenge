# Brim AP Automation — Harborline Logistics

Inbox-to-payment accounts payable automation for the Brim sponsor challenge (McGill CodeJam 2026).

## Demo
_Video link, screenshots, demo logins per role_

## Run it
```bash
cp .env.example .env   # add LLM_API_KEY
docker compose up --build
# frontend: http://localhost:5173   API: http://localhost:8088/api   Swagger: http://localhost:8088/swagger-ui.html
```

The backend seeds every file in `dataset_given/` on first start. To wipe and reload in seconds:

```bash
scripts/reset-and-seed.sh          # or: .\scripts\reset-and-seed.ps1
```

It prints the loaded row count of every table next to the count in `dataset_given/README.md`.

### Local dev (without Docker for the app)
```bash
docker compose up -d db
cd backend && ./gradlew bootRun     # needs JDK 21+, reads ../.env vars from your shell
cd frontend && npm install && npm run dev
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
