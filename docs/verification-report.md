# Pre-review verification report

Run on 2026-11-21 against branch `phase9-verification-fixes` (stacked on PRs #1–#8), Windows 11 host, Docker 29.1.3.
Every result below was produced by a command in this pass; nothing is carried over from earlier runs.

## Summary

| Area | Result |
| --- | --- |
| Each phase branch builds and passes its own tests | ✅ phase1 4 · phase2 10 · phase3 24 · phase4 67 · phase5 81 · phase6 81 · phase7 81 · phase8 88 · this branch **93** |
| Frontend | ✅ `tsc -b` clean · `oxlint` 0 errors (4 warnings, D-076) · production build OK · `npm audit` 0 vulnerabilities |
| `docker compose up --build` from empty volumes | ✅ answering in 15 s, seed matches the dataset README, 0 ERROR lines in the backend log |
| Scripts | ✅ `reset-and-seed.sh` / `.ps1` ALL MATCH · `verify_mess_table.py --process` **24/24** · `eval_extraction.py` **726/728 (99.7%)** offline path · `flag_report.py` · `build_ground_truth.py` regenerates byte-identical output |
| PRD API contract | ✅ all 44 endpoints present (57 operations live, 13 extra: bonuses and helpers) |
| Role enforcement, probed live | ✅ 54/54 non-public operations → 401 anonymous · 28/28 writes → 403 as Auditor |
| Secrets | ✅ gitleaks: no leaks in history (15 commits) or working tree · `.env` never committed |
| SAST (semgrep: java, security-audit, secrets, typescript, dockerfile) | ✅ after fixes (2 findings fixed, below) |
| Scripts lint | ✅ ruff (E,F,W,B,S) and shellcheck clean after fixes |

## Findings fixed in this PR

| # | Severity | Finding | Fix | Proof |
| --- | --- | --- | --- | --- |
| 1 | High | Anonymous POST/PUT passed the filter chain (`AnonymousAuthenticationToken.isAuthenticated()` is true); refused only by `@PreAuthorize` after the body was parsed and validated — anonymous callers saw 400 validation errors instead of 401. No action executed. | Filter rule requires a real `CurrentUser` principal (D-071) | `SecurityConfigTest` seen red ("expected 401 but was 400"), now green; live probe 54/54 → 401 |
| 2 | Medium | Backend container ran as root | Unprivileged `app` user owning `/storage` (D-072) | `id` in container → `uid=999(app)`; full batch processed and documents stored |
| 3 | Medium | No CI: PRs #1–#8 have no checks | `.github/workflows/ci.yml`, SHA/digest-pinned, least-privilege, actionlint-clean (D-074) | Runs on this PR |
| 4 | Low | SQL built by string concatenation in `IdGenerator` (constants only, not exploitable) | Bound parameter (D-073) | invoice and payment ids generated during the 24/24 run |
| 5 | Low | ruff: ambiguous `l` names, lambdas assigned as methods in scripts | Renamed / `def` | ruff clean; ground truth unchanged |

## Phase requirements vs. PRs

| PR | Requirement (from the build brief) | Status |
| --- | --- | --- |
| #1 | Initializr project (Web, JPA, Security, Validation, PostgreSQL, Lombok, springdoc), Vite react-ts, compose db+backend+frontend, all JPA entities, seeder for every dataset file, reset-and-seed, counts verified | ✅ (no `po_lines` table: the dataset has no PO lines, D-075) |
| #2 | Session auth + "log in as any user", role enforcement on every endpoint, InvoiceWorkflowService, append-only hash-chained audit | ✅ (enforcement gap #1 above fixed here) |
| #3 | Intake classification, pdftotext, Tesseract/LLM for images, LLM structuring to strict JSON, normalization (French, TPS/TVQ), arithmetic checks, vendor matching, eval script over 56 files | ✅ — **the LLM path has never run: `LLM_API_KEY` in `.env` is empty**; everything measured is the offline path |
| #4 | Coding suggestions from history; every catalog rule its own class with a unit test on the PRD's example | ✅ 32 rules + V06 |
| #5 | Routing from the structured policy table (delegation, self-approval, same-person-twice, terminated, limits), approvals, payments with release limits + SoD, vendor onboarding, bank-change verification | ✅ |
| #6 | All 9 screens, role-aware, role switcher | ✅ |
| #7 | Whole batch processed; every "Mess found" row gets its flag | ✅ 24/24 |
| #8 | Bonuses in PRD order after phase 7 passes | ✅ fraud alerts, reconciliation, recommendation, analytics |

## Not verified / open items

- **LLM extraction** (Claude via the Anthropic Java SDK) is untested live — no API key. Add one to `.env`, restart, run `python scripts/eval_extraction.py`.
- **Nothing is merged**: the PRs are stacked; merge #1 → #9 in order. Branch protection on `main` is not configured.
- No end-to-end browser test suite; the UI was checked by hand in the in-app browser as AP Clerk, Finance Manager and Auditor.
- Docker builds fetch from Maven Central; on a flaky network the first attempts can fail (two did during this pass) — just re-run.
