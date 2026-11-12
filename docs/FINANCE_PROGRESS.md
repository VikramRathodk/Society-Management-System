# Finance & Accounting: build progress

Running log of the finance module build (plan: [modules/finance.md](modules/finance.md)). Updated after
every step. Backend branch `feature/finance` (cut from `master` 039a21c); merged to `master` and pushed on 2026-11-12.

## Status

| Step | What | State | Commit | Tests after |
|---|---|---|---|---|
| Plan | Plan + review upgrades (`modules/finance.md`, `modules/finance-upgrades.md`) | ✅ | 3671778 | |
| 1 | Books and chart: chart of accounts with groups, bank accounts, start books, openings, ledger, audit log (V19) | ✅ | 18d4739 | 279 |
| 2 | Vouchers: receipt/payment/contra/journal, approval, void by reversal, lock date, documents, day book, cash & bank (V20) | ✅ | 06cce05 | 296 |
| 3 | Maintenance credit and refunds (V21) | ✅ | 0010beb | 310 |
| 4 | Maintenance posts to the books automatically; maintenance check | ✅ | 5ea4734 | 322 |
| 5 | Statements: trial balance, I&E, R&P, balance sheet, resident summary, CSV; Postman folder 17 | ✅ | e5d520f | 328 |
| 6 | Web: Finance section in the Angular app (web branch `feature/finance`) | ✅ (smoke-checked) | web 6b58360 | web 137 |

## Decisions taken without the product owner (2026-11-10)

The product owner asked me to decide on my own and record it. Each can be revisited; details are under
"Built so far" in `modules/finance.md`.

- **Step 4:** voids and cancellations are worked out from the current maintenance state, not by reversing
  old vouchers (stays right for history from before the books started; unbooked originals go to the
  General fund). System vouchers are attributed to the signed-in user. Opening-balance bills entered in
  maintenance are booked against the General fund. Waiting refunds count as member advance on both sides.
- **Step 5:** statements group by top-level group; Receipts & Payments hides transfers and treats the
  opening voucher as the opening balance; the balance sheet shows the computed surplus with the funds
  (no closing entries until V1.1); the resident summary shows group totals and cash + bank only.

## Step 5 plan (as executed)

1. Period-lines query (with voucher type) for Receipts & Payments.
2. `StatementService`: trial balance, I&E, R&P, balance sheet, monthly summary.
3. `FinanceReportController`: JSON + CSV for each statement, ledger and day-book CSV, resident summary.
4. Postman folder 17 "Finance"; full collection run on a test server (8081).
5. `FinanceStatementsIntegrationTest` with a hand-checked worked example; docs.

## Things to know

- Tests and the local dev server share the database `society_management_db`; running the suite applies
  new migrations (V19–V21 so far) to it. Older code on `master` ignores migrations it doesn't know.
- Postman full run (step 3, test server on 8081): 211 requests, 292 assertions, 1 failure that predates
  finance: cleanup can't delete the test resident because the resident sent an invitation
  (`invitation_invited_by_fkey`).

## Step 6 (web), as executed

- Web branch `feature/finance` (from web `master` eb71a0d). Pages under `/finance`: Overview (setup wizard
  before the books start), Entries + detail, Accounts + ledger, Reports, Settings, Audit log. Nav item
  "Finance" (FINANCE.ACCOUNT.VIEW); palette: Record income, Record expense, Financial statements.
- Maintenance: dues show credit and a Refund action; Refunds tab (approve/reject/withdraw/void);
  payment dialog offers the books' cash/bank account; bills and receipts mark "from advance".
- Residents: dashboard "Society finances" card, only when the society shares the summary.
- Checks: 137/137 unit tests, production build clean (initial 338 kB). Smoke check in headless Chrome
  on a throwaway society (test servers 8081/4300): setup, income, void, accounts, ledger, payment with
  overpayment to credit, refund, settings check, audit log and resident summary all worked, with no
  console errors. Three steps failed only because of my test selectors (Material toggles are radios,
  the account dropdown had no accessible name), not app errors; the waiting-payment approval flow
  was therefore not clicked through in the browser (it is covered by backend tests).
- The smoke test left a society "Finance Smoke <timestamp>" in the local dev database (books started,
  so it can't be deleted).

## Left to do

- (done 2026-11-12) Merged both `feature/finance` branches to `master` (backend fast-forward to c59ea34, web merge 735f68c) and pushed.
- (done) Playwright e2e `e2e/finance.spec.ts` in the web repo: 6/6 against test servers, including the committee approving a waiting expense. The dropdowns were labelled correctly; the earlier smoke failures were the test clicking an empty Material select's floating label. Run it against your 8080/4200 servers with `npx playwright test e2e/finance.spec.ts`; each run leaves one "E2E Finance …" society behind.
- V1.1 items in `modules/finance.md` (reconciliation, year closing, recurring expenses, cheques, petty cash).
- Accounting questions for a society accountant ("Needs an accountant" in `modules/finance.md`).

## V1.1: bank reconciliation

Plan: [modules/finance-reconciliation.md](modules/finance-reconciliation.md). Backend branch
`feature/bank-reconciliation` (from `master` 8687ecb). Product owner chose all four recommended options
on 2026-11-12: column mapping per bank account, suggest + confirm matching, many book entries to one bank
line, reopen the latest only with a reason.

| Step | What | State | Commit | Tests after |
|---|---|---|---|---|
| Plan | `modules/finance-reconciliation.md` | ✅ (waiting for review) | | |
| R1 | V22, mapping, preview, statements, import | | | |
| R2 | Matching, exclude, void handling, voucher from bank line | | | |
| R3 | Complete/reopen, BRS, audit, Postman | | | |
| R4 | Web Reconcile page, e2e | | | |
