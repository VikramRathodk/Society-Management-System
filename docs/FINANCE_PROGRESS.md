# Finance & Accounting: build progress

Running log of the finance module build (plan: [modules/finance.md](modules/finance.md)). Updated after
every step. Backend branch `feature/finance` (cut from `master` 039a21c); nothing is pushed.

## Status

| Step | What | State | Commit | Tests after |
|---|---|---|---|---|
| Plan | Plan + review upgrades (`modules/finance.md`, `modules/finance-upgrades.md`) | ✅ | 3671778 | |
| 1 | Books and chart: chart of accounts with groups, bank accounts, start books, openings, ledger, audit log (V19) | ✅ | 18d4739 | 279 |
| 2 | Vouchers: receipt/payment/contra/journal, approval, void by reversal, lock date, documents, day book, cash & bank (V20) | ✅ | 06cce05 | 296 |
| 3 | Maintenance credit and refunds (V21) | ✅ | 0010beb | 310 |
| 4 | Maintenance posts to the books automatically; maintenance check | ✅ | 5ea4734 | 322 |
| 5 | Statements: trial balance, I&E, R&P, balance sheet, resident summary, CSV; Postman folder 17 | ✅ | see git log | 328 |
| 6 | Web: Finance section in the Angular app | ⏳ | | |

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
