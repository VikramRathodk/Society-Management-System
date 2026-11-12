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
- **A voided entry and its reversal cancel out.** When neither has cleared, they are cleared together
  without a bank line (shown as "cancelled in the books"). When the original had already cleared (a
  bounced cheque), the reversal is an ordinary entry waiting for the bank's matching debit.
- **Voiding a voucher whose bank line is matched in an open reconciliation** removes that match. In a
  completed one the match stays (the bank did see it); the reversal waits for its own bank line.
- **The opening voucher counts as cleared**: the bank-account opening is the bank's balance on the day
  the books started. Cheques that were uncleared on that day are a known V1.1 limit (enter the opening
  as the bank's balance, record those cheques as entries after the start).
- **Periods follow each other.** Per bank account, a new statement starts the day after the last one
  ended and its opening balance must equal the previous closing balance. The first statement may start
  on any day on or after the books' start date.
- **The same bank transaction can't come in twice.** Each imported row gets a fingerprint (date, amount,
  direction, description, running balance); a row already imported for that bank account is skipped and
  reported. Rows dated outside the statement period are rejected.
- **Completing** needs every bank line resolved (matched, or excluded with a reason for a bank error
  that the bank reversed) and a zero difference:

  ```
  statement closing balance
    = book balance on the end date
    − receipts in the books not yet cleared
    + payments in the books not yet cleared
  ```

  Uncleared book entries carry over to the next month. The completed reconciliation keeps a snapshot of
  these numbers (the bank reconciliation statement, BRS).
- **Books lock.** Completing does not lock the books; the secretary still sets the lock date. A
  completed reconciliation's book lines can't be unmatched until it is reopened.

## Data (V22)

| Table | What |
|---|---|
| `finance_bank_account` + `statement_mapping JSONB` | Saved column mapping: header row, date column + format, description, reference, debit/credit columns *or* amount + Dr/Cr column, balance column |
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

## API (under `/api/v1/societies/{id}/finance`)

- `PUT /bank-accounts/{accountId}/statement-mapping`, and `POST …/statement-preview` (multipart CSV →
  the first 20 parsed rows and any errors, nothing saved).
- `POST /bank-accounts/{accountId}/statements` (dates, opening and closing balance) and
  `GET` list; `GET /statements/{sid}` with totals and the difference.
- `POST /statements/{sid}/imports` (multipart CSV): parse, skip duplicates, run auto-match.
- `GET /statements/{sid}/lines?state=`, `GET /statements/{sid}/book-entries?cleared=false`,
  `GET /statements/{sid}/lines/{lid}/suggestions`.
- `POST /statements/{sid}/matches` (bank line + book lines), `DELETE /matches/{mid}`,
  `POST /statements/{sid}/auto-match`.
- `POST /statements/{sid}/lines/{lid}/exclude` (reason), `…/include`,
  `POST /statements/{sid}/lines/{lid}/voucher` (receipt or payment, other account, narration; the
  voucher is matched to the line when it posts, straight away or after approval).
- `POST /statements/{sid}/complete`, `POST /statements/{sid}/reopen` (reason).
- `GET /statements/{sid}/brs` and `…/brs.csv`; the ledger shows each bank line's cleared date.

## Build steps

| Step | What | Done when |
|---|---|---|
| R1 | V22, permissions, setting; column mapping, preview, statements, import with duplicates | Import tests: several bank layouts, Dr/Cr column, Indian amounts (1,23,456.00), date formats, header junk rows, duplicates, out-of-period rows |
| R2 | Book entries, suggestions, auto-match, manual match/unmatch, exclude, void handling, voucher from a bank line (incl. after approval) | Matching tests including the ₹15,000 five-cheque deposit and a bounced cheque |
| R3 | Complete with BRS snapshot, reopen latest, audit, BRS JSON/CSV, cleared date on the ledger; Postman folder | Hand-checked BRS worked example; full Postman run |
| R4 | Web: Reconcile page per bank account (two-column bank vs books, suggestions, create voucher from line, complete/reopen), mapping wizard, BRS in Reports | Unit tests, build, Playwright e2e on a fresh society |

Each step ends with the suite passing and its own commit.

## Not in this round

- Excel (XLS/XLSX) and PDF statements: save as CSV from the bank's site for now.
- Bank feeds / account aggregator.
- Cheque status (issued, deposited, cleared, bounced) is the separate V1.1 cheque item; reconciliation
  is what will later mark a cheque cleared.
