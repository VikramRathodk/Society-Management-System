# Finance: bank reconciliation (V1.1)

First V1.1 item of [finance.md](finance.md). Checks the books against the bank's statement, month by
month, per bank account. Planned 2026-11-12; build log in [../FINANCE_PROGRESS.md](../FINANCE_PROGRESS.md).

## Decisions (product owner, 2026-11-12)

1. **CSV, mapped once per bank account.** On the first upload the treasurer says which column is the
   date, description, debit, credit, balance; the mapping is saved on the bank account and reused. Works
   for any bank, co-op banks included. No bank presets.
2. **Suggest, user confirms.** A bank line with exactly one book entry of the same amount and direction
   within N days (setting `reconciliationMatchDays`, default 3) is matched automatically; anything with
   more than one candidate is shown as suggestions. Every match can be undone until the reconciliation
   is completed.
3. **Many book entries to one bank line.** Several book entries may match one bank line when they add
   up to it (five cheques deposited together). One book entry is never split across bank lines.
4. **Reopen the latest only, with a reason.** Only the most recent completed reconciliation of a bank
   account can be reopened, by someone with `FINANCE.RECONCILIATION.COMPLETE`, and the reason is
   audited.

## Rules

- **Reconciliation never edits or deletes a voucher.** It only records which bank line each book line
  cleared against. Anything missing from the books (bank charges, interest, a forgotten receipt) is
  recorded as a normal voucher, created from the bank line, which goes through the usual approval and
  lock date.
- **Book entries** are the lines of POSTED and VOID vouchers on that bank ledger (a void stays in the
  books with its reversal, as in the statements). Pending and rejected vouchers aren't in the books and
  can't be matched.
- **A voided entry and its reversal cancel out.** When neither has cleared and both are dated within
  the statement, auto-match clears them together without a bank line ("cancelled in the books"). Any
  two entries that cancel exactly (a maintenance payment and its void, which are separate system
  vouchers) can be paired by hand. When the original had already cleared (a bounced cheque), the
  reversal is an ordinary entry waiting for the bank's matching debit.
- **Voiding never removes a match** (changed in R2 from "removes it in an open statement"). The bank did
  see the money: a cheque deposited and returned in the same month keeps the deposit matched to the
  credit, and the reversal matches the return. A wrong match is fixed by unmatching it, on purpose.
- **Only entries dated within the statement** (up to its end, carried-over ones included) can be
  matched in it, so the BRS arithmetic holds. Its end can't move before an entry it cleared.
- **Auto-match** runs after every import and on request: a bank line and an entry of the same amount
  and direction, within `reconciliationMatchDays`, match only when each is the other's only candidate.
  Suggestions show same-amount entries within 30 days (nearest first, flagged inside or outside the
  window) and groups of 2–8 entries that add up to the line (from 30 days before to the window after).
- **Recording from a bank line.** Money in becomes a receipt, money out a payment, and another
  cash/bank account a transfer, dated on the bank line with the usual voucher rules (approval, lock
  date, no member accounts). Posted: it clears the line at once. Waiting for approval: the line is held
  (no match, exclude or second voucher) until it is decided; approval clears it, rejection or withdrawal
  frees it.
- **The opening voucher counts as cleared**: the bank-account opening is the bank's balance on the day
  the books started. Cheques that were uncleared on that day are a known V1.1 limit (enter the opening
  as the bank's balance, record those cheques as entries after the start).
- **Periods follow each other.** Per bank account, a new statement starts the day after the last one
  ended and its opening balance must equal the previous closing balance. The first statement may start
  on any day on or after the books' start date, **and must open at the books' balance of that account
  at the start of that day** (added in R3). Entries dated before an account's first statement count as
  cleared: that is only true if the bank and the books agreed that day. If they didn't (cheques still
  out), start the first statement earlier, on the books' start date, so those entries can be matched.
- **The same bank transaction can't come in twice.** Each imported row gets a fingerprint (date, amount,
  direction, description, running balance); a row already imported for that bank account is skipped and
  reported. The nth identical row of a file (two ₹500 UPI payments from one person on one day) gets its
  own fingerprint, so both come in, and the same file imported again adds nothing. Rows dated outside
  the statement period are skipped and reported (banks export wider ranges), not imported.
- **A file is imported whole or not at all.** Rows without a date (account details, opening balance,
  summary) or without an amount are skipped and listed; a row with a date but an amount that can't be
  read refuses the whole file. Newest-first exports are turned round. A running balance that doesn't
  follow from the amounts is a warning, not an error. An import can be taken back out while the
  statement is open.
- **Completing** needs every bank line resolved (matched, a voucher recorded from it, or excluded with
  a reason), the imported lines adding up to the closing balance, and a zero difference:

  ```
  statement closing balance
    = book balance on the end date
    − receipts in the books not yet cleared
    + payments in the books not yet cleared
    + bank lines set aside (this and earlier statements of the account)
  ```

  Set-aside lines count cumulatively, so a wrong credit in April and the bank's reversal in May show as
  +500 on April's BRS and 0 on May's. Uncleared book entries carry over to the next month; "uncleared
  as of a statement" means not cleared by it or an earlier one, so April's BRS keeps showing what was
  out at the end of April after May clears it. The completed statement keeps the BRS totals (book
  balance, uncleared receipts and payments); its BRS lists are worked out again and `booksChanged`
  flags when they no longer give the kept totals (an entry backdated into a reconciled month).
- **Reopening** (`FINANCE.RECONCILIATION.COMPLETE`, reason required) only works on an account's latest
  statement, and only while no later one exists: an open later statement must be deleted first, since
  one statement is open at a time. The kept totals are cleared; the matches stay.
- **Books lock.** Completing does not lock the books; the secretary still sets the lock date. A
  completed reconciliation's book lines can't be unmatched until it is reopened.

## Data (V22)

| Table | What |
|---|---|
| `finance_statement_mapping` | Saved column mapping per bank account (typed columns rather than JSON): separator, header row, date column + format, description, reference, then debit/credit columns, *or* amount + Dr/Cr column, *or* a signed amount; balance column |
| `finance_bank_statement` | One per period per bank account: dates, opening and closing balance, status `OPEN` / `COMPLETED`, prepared/completed/reopened by and when, reopen reason, BRS snapshot (book balance, uncleared receipts, uncleared payments) |
| `finance_bank_statement_import` | Each upload: file name, row count, imported, skipped as duplicates, by, at. The original CSV kept through `common/storage` |
| `finance_bank_line` | One per statement row: date, description, reference, debit, credit, balance, fingerprint (unique per bank account), state `UNMATCHED` / `MATCHED` / `EXCLUDED`, exclude reason, voucher created from it |
| `finance_bank_match` | Book line ↔ bank line: `finance_line_id` unique (one bank line per book line), `bank_line_id`, `auto` flag, by, at. A null bank line with a `pair_line_id` = "cancelled in the books" |

Checks in the database: a match's book line is on the statement's bank ledger; the matched book lines'
total equals the bank line (deferred trigger, like the voucher balance check).

## Permissions and setting

| Code | Who by default | Allows |
|---|---|---|
| `FINANCE.RECONCILIATION.VIEW` | Committee, Secretary | See statements, matches, the BRS |
| `FINANCE.RECONCILIATION.UPDATE` | Secretary | Map columns, import, match, unmatch, exclude, create vouchers from bank lines |
| `FINANCE.RECONCILIATION.COMPLETE` | Secretary | Complete and reopen |

Creating a voucher from a bank line also needs `FINANCE.VOUCHER.CREATE`. Setting
`reconciliationMatchDays` (FINANCE group, 0–15, default 3). Audit: new entity `RECONCILIATION`, actions
`IMPORT`, `MATCH`, `UNMATCH`, `EXCLUDE`, `COMPLETE`, `REOPEN`.

## API

Built in R1 (`FinanceReconciliationController`):

- `GET|PUT /api/finance/bank-accounts/{accountId}/statement-mapping` (GET also lists the 12 date
  formats).
- `POST /api/finance/bank-accounts/{accountId}/statement-preview`, multipart `file` and an optional
  JSON part `mapping`: the first 30 raw rows, the mapping used (`GIVEN`, `SAVED`, `SUGGESTED` from the
  column names, or `NONE`) and the lines it reads, with ignored rows, errors and balance warnings.
  Nothing is saved.
- `POST /api/finance/bank-accounts/{accountId}/statements`; `GET /api/societies/{id}/finance/statements?accountId=`;
  `GET|PATCH|DELETE /api/finance/statements/{sid}` (change and delete while open). A statement shows its
  line counts by state, money in and out, and `linesDifference` (closing balance minus opening + lines:
  non-zero while lines are missing).
- `POST|GET /api/finance/statements/{sid}/imports` (multipart `file`); `DELETE /api/finance/statement-imports/{iid}`;
  `GET /api/finance/statement-imports/{iid}/file`.
- `GET /api/finance/statements/{sid}/lines?state=` (each with the entries it cleared and any voucher
  recorded from it).

Built in R2 (under `/api/finance/statements/{sid}`); the import response also says how many lines
auto-match cleared:

- `GET …/book-entries?cleared=false|true`, `GET …/lines/{lid}/suggestions`, `POST …/auto-match`.
- `POST|DELETE …/lines/{lid}/match` (body `bookLineIds`), `POST …/lines/{lid}/exclude` (reason),
  `POST …/lines/{lid}/include`.
- `POST …/lines/{lid}/voucher` (`accountId`, optional payee, narration, reference; also needs
  `FINANCE.VOUCHER.CREATE`).
- `POST …/pairs` (`firstLineId`, `secondLineId`), `DELETE …/pairs/{bookLineId}`.

Built in R3:

- `GET …/brs` (totals, the outstanding receipts and payments, set-aside lines, difference,
  `canComplete` and the reasons it can't yet) and `GET …/brs.csv`.
- `POST …/complete` (returns the BRS), `POST …/reopen` (reason).
- The ledger of a bank account gives each entry's `clearedOn` (the bank line's date; a pair's later
  date; the opening's own date). Entries before the first statement have none, though they count as
  cleared.
- Postman folder 18 "Bank reconciliation" (cleanup is now 19): it builds today's statement from the bank
  ledger at run time, because the books in the collection start today and a fixed CSV fixture couldn't
  carry the date; uploads go as hand-built multipart bodies.

## Build steps

| Step | What | Done when |
|---|---|---|
| R1 ✅ | V22, permissions, setting; column mapping, preview, statements, import with duplicates | Import tests: several bank layouts, Dr/Cr column, Indian amounts (1,23,456.00), date formats, header junk rows, duplicates, out-of-period rows |
| R2 ✅ | Book entries, suggestions, auto-match, manual match/unmatch, exclude, void handling, voucher from a bank line (incl. after approval) | Matching tests including the ₹15,000 five-cheque deposit and a bounced cheque |
| R3 ✅ | Complete with BRS snapshot, reopen latest, audit, BRS JSON/CSV, cleared date on the ledger; Postman folder | Hand-checked BRS worked example; full Postman run |
| R4 ✅ | Web: Reconcile page per bank account (two-column bank vs books, suggestions, create voucher from line, complete/reopen), mapping wizard, BRS in Reports | Unit tests, build, Playwright e2e on a fresh society |

Each step ends with the suite passing and its own commit.

## Not in this round

- Excel (XLS/XLSX) and PDF statements: save as CSV from the bank's site for now.
- Bank feeds / account aggregator.
- Cheque status (issued, deposited, cleared, bounced) is the separate V1.1 cheque item; reconciliation
  is what will later mark a cheque cleared.
