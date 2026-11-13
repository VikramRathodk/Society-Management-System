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
| Plan | `modules/finance-reconciliation.md` | ✅ (approved 2026-11-12) | 8fbd919 | |
| R1 | V22, mapping, preview, statements, import | ✅ | 2288aa4 | 349 |
| R2 | Matching, exclude, void handling, voucher from bank line | ✅ | 8ce2d62 | 361 |
| R3 | Complete/reopen, BRS, audit, Postman | ✅ | (this commit) | 369 |
| R4 | Web Reconcile page, e2e | ✅ | web ab3cf6c | web 159 unit, e2e 2/2 |

### R1, as executed (2026-11-12)

- V22: `finance_statement_mapping` (typed columns instead of the planned JSON), statements, imports,
  bank lines, matches (table and both triggers in place; used from R2), three permissions.
  Setting `reconciliationMatchDays` (default 3).
- `StatementCsv` reads the CSV on its own (unit-tested): UTF-8 or Windows-1252, any of `, ; tab |`,
  12 date formats, Indian amounts, Dr/Cr suffixes and brackets, three amount layouts (withdrawal +
  deposit, amount + Dr/Cr, signed). It guesses the mapping from the column names (checked on
  HDFC-, SBI- and ICICI-style samples).
- Decided while building: rows outside the period are **skipped and reported**, not refused (banks
  export wider ranges than a month). A file with any unreadable transaction row is refused whole.
  An import can be removed while the statement is open. One open statement per bank account.
- Names: `BankStatementService` / `BankStatementRepository`, because `StatementService` is the
  financial statements service from step 5.
- Tests: `StatementCsvTest` (13), `BankStatementIntegrationTest` (8); full suite 349/349.

### R2, as executed (2026-11-12)

- `ReconciliationService` (matching) on top of `BankStatementService` (statements and imports);
  `BankMatchRepository` reads the bank ledger's uncleared entries straight from `finance_line`.
  Approval and rejection reach it through the existing `VoucherDecided` event; vouchers know nothing
  of reconciliation.
- Changed from the plan: **voiding no longer removes a match**, even in an open statement. A cheque
  deposited and bounced in the same month must keep the deposit matched to the bank's credit and let
  the reversal match the return; removing the match would leave both bank lines with nothing to match.
- Decided while building: only entries dated up to the statement's end can be matched in it; auto-match
  needs each side to be the other's only candidate; any two exactly cancelling entries can be paired
  by hand (maintenance voids are separate system vouchers, not linked reversals); a bank line with a
  voucher waiting for approval is held until it's decided.
- Tests: `ReconciliationIntegrationTest` (12), including the five-cheque deposit, a bounced cheque,
  approval and rejection, and the two database triggers (made immediate in the test, since the total
  check is deferred to commit). Full suite 361/361.

### R3, as executed (2026-11-12)

- BRS (`ReconciliationService.brs`): bank = books − uncleared receipts + uncleared payments + set-aside
  lines (cumulative over the account's statements). Completing needs no unmatched lines, the lines
  adding up to the closing balance, and a zero difference; it keeps the totals, and a completed BRS
  flags `booksChanged` when a backdated entry no longer gives them. Reopen: latest statement only, no
  later statement, reason required; matches stay.
- Found while building, added: **entries before an account's first statement count as cleared, and
  the first statement must open at the books' balance that day.** Without it, a society whose books
  start in April but which starts reconciling in October could never complete: every April–September
  entry would stay "uncleared". "Uncleared" is now as of the statement (not cleared by it or an
  earlier one), so an old BRS still shows what was outstanding then.
- Ledger lines carry `clearedOn` on bank accounts (`LedgerRow` gained the line id).
- Tests: `BankReconciliationStatementIntegrationTest` (8) on a hand-checked April/May example; full
  suite 369/369.
- Postman folder 18 "Bank reconciliation" (22 requests; cleanup renumbered 19). Full newman run on a
  test server (8081): 260 requests, 386 assertions, 1 failure, the old one (cleanup can't delete the
  test resident because of their invitation).

### R4, as executed (2026-11-13)

- Web branch `feature/bank-reconciliation` cut from `feature/ui-modernize` (not master), at the user's
  choice, so it builds on the new status pills and dashboard; merging it brings ui-modernize along.
  Web commit ab3cf6c.
- Finance tab "Reconcile" (`/finance/reconcile[/:accountId]`): statements per bank account, newest
  first; New statement proposes the month after the last one (or last month for the first); Statement
  columns opens the import dialog in save-only mode.
- Import dialog: reads the file with the saved or guessed mapping, shows the raw rows (header row
  highlighted) and the parsed lines, re-reads 400 ms after any column change, and saves the mapping
  only if it changed, then imports. Import is disabled while any row can't be read.
- Statement page (`/finance/reconcile/statements/:id`): stats; a warning while the lines don't reach
  the closing balance; tabs Match / Reconciliation statement / Imports. Match: bank lines (filter by
  state) beside entries waiting to clear or cleared here; picking a line shows suggestions (single and
  groups) and tick-to-match with a running total; line menu: record entry, set aside, unmatch, bring
  back; with no line picked, two ticked entries that cancel can be paired. Read-only once completed.
- Bank book: an "In the bank" column (clearedOn) once any entry has cleared. Audit log: Reconciliation
  filter.
- Found by the e2e: on the books' first day the ledger's opening is 0 (the opening voucher is dated
  that day), while the backend counts opening balances as before day one. The "Use the books'
  balance" button adds the day's OPENING lines (`booksOpening`).
- Checks: web unit tests 159/159, production build, `e2e/reconciliation.spec.ts` 2/2 and
  `e2e/finance.spec.ts` 6/6 on test servers (backend 8081 on this branch, web 4300 proxying to it).

### Next

- Nothing is pushed. Backend `feature/bank-reconciliation` (R1–R3 + docs) and web
  `feature/bank-reconciliation` (R4, on top of `feature/ui-modernize`) await the user's review, then
  merge to master in both repos (the user pushes).
