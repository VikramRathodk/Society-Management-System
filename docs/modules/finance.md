# Finance & Accounting (`finance`)

The society's books: what came in, what went out, where the money sits, and the statements an annual
audit asks for. Module 15 in [MODULES.md](../MODULES.md).

**Status:** 🟡 being built. Backend done 2026-11-10 (steps 1–5: books, vouchers, maintenance credit and refunds, maintenance postings, statements); step 6 (web) next. Builds on Maintenance (module 14). Revised
after the review in [finance-upgrades.md](finance-upgrades.md); its "Review outcome" table says what
was adopted and why.

> Statutory treatment (fund classification, year-end closing, statement formats) must be checked by a
> practising Maharashtra cooperative housing society accountant before commercial release. See
> "Needs an accountant" below.

## Decisions
| # | Decision |
|---|---|
| 1 | **Double-entry underneath, simple forms on top.** Every entry is a balanced voucher (debits = credits) on a per-society chart of accounts. Users fill in "Income", "Expense" or "Transfer"; only the secretary sees a raw journal form. |
| 2 | **Maintenance posts automatically**, in the same transaction, idempotently (one posting per source event, enforced by a unique key). |
| 3 | **Approval is a per-society setting** (`financeApprovalAbove`, rupees, 0 = off, default off). Expenses, journals and maintenance refunds above it wait for a second person. (Was `expenseApprovalAbove`; renamed because refunds use it too.) |
| 4 | **Residents see a summary only, when the society allows it** (`residentsSeeFinanceSummary`, default off). |
| 5 | **Advance payments are accepted** (review item 2). A payment above what the flat owes keeps the excess as the flat's credit, booked as "Advance from members", and used up automatically when the next bills are issued. A society can turn this off (`acceptAdvancePayments`). |
| 6 | **Unused credit can be refunded** (review item 3), through the same approval threshold. |
| 7 | **Multiple bank accounts** from V1 (review item 5), with an account hierarchy (item 11), an insert-only audit trail (item 1) and several attachments per voucher (item 10). |

Defaults taken: financial year April–March (a setting); payee is free text until the Vendor module (12)
exists; no clearing account in V1 (a cheque counts when it's recorded, as maintenance does today).

## Scope

| V1 | V1.1 | Later |
|---|---|---|
| Chart of accounts with group hierarchy | Bank statement import + reconciliation | GST / TDS |
| Multiple bank accounts | Financial year closing workflow | Fixed assets & depreciation (Asset module 19) |
| Start books, opening balances | Recurring expense templates | FD / investment register |
| Receipt, Payment, Contra, Journal, Opening, System vouchers | Cheque lifecycle (deposited, cleared, bounced) | Budgets, budget vs actual |
| Approval (setting) with pushes, void by reversal, lock date | Petty cash counts | Vendor invoices / payables (Vendor module 12) |
| Several attachments per voucher | Other notifications (missing document, period close) | Gateway receipts (Payment module 16) |
| Audit trail | | |
| Maintenance: advances, credit use, refunds | | |
| Automatic, idempotent maintenance postings | | |
| Ledgers, day book, cash/bank book, trial balance, I&E, R&P, balance sheet | | |
| Resident summary, maintenance check, CSV | | |

## Chart of accounts

Account types: `ASSET`, `LIABILITY`, `FUND`, `INCOME`, `EXPENSE`. `FUND` is separate because society funds
(sinking, repair, general) are owed to members, not income, and the balance sheet shows them on their own.

**Hierarchy.** An account is either a **group** (can't be posted to, only holds children) or a
**ledger** (can be posted to). `parentId` can nest groups to any depth. Rules: a child has its parent's
type, a parent must be a group, no cycles (checked on every move), and a group with children can't be
turned into a ledger. Each type has one root group, seeded and undeletable.

**Codes.** Unique per society. The default chart uses 1xxx assets, 2xxx liabilities, 3xxx funds, 4xxx
income, 5xxx expenses; this is a convention, not enforced.

**System accounts** are the ones postings rely on. They can be renamed and moved between groups of the
same type, but not deleted, deactivated or retyped:

| systemKey | Default name | Type |
|---|---|---|
| `CASH` | Cash in hand | ASSET |
| `MEMBER_DUES` | Maintenance receivable from members | ASSET (per-flat sub-ledger) |
| `MEMBER_ADVANCE` | Advance maintenance from members | LIABILITY (per-flat sub-ledger) |
| `MAINTENANCE_INCOME` | Maintenance charges | INCOME |
| `LATE_FEE_INCOME` | Interest / late charges | INCOME |
| `WRITE_OFF` | Maintenance waived | EXPENSE |
| `GENERAL_FUND` | General fund | FUND (takes the opening difference and, until year closing exists, the surplus in reports) |

The default chart also has common accounts (sinking fund, repair fund, members' deposits, electricity,
water, security, housekeeping, lift AMC, repairs, salaries, bank charges, hall rent, interest income,
donations), grouped. Societies edit it freely.

An account with postings can't be deleted, only deactivated. Inactive ledgers and groups receive no new
postings but stay in reports.

**Per-flat sub-ledgers.** Lines on `MEMBER_DUES` and `MEMBER_ADVANCE` always carry a `flatId`; lines on
other accounts never do. A database check enforces both.

## Bank accounts

`finance_bank_account` describes a bank ledger (1:1 with an ASSET ledger under "Bank accounts"): bank
name, display name, account type (SAVINGS / CURRENT), **last 4 digits** of the account number, IFSC,
active. Only the last 4 digits are stored, so there is nothing secret to protect; no credentials, ever.

The books keep a default receiving account per payment method (`CASH` → Cash in hand, `CHEQUE` / `UPI` /
`BANK_TRANSFER` → the default bank) and a default paying account. Every receipt, payment and contra picks
its actual cash/bank ledger (defaulted from these). A maintenance payment can name the account it went
into (see Maintenance below). A deactivated bank account keeps its history.

## Starting the books

Nothing posts until a society starts its books: `POST .../finance/start` with `startDate`, the opening
balance of each ledger (cash, banks, funds, deposits…), and the bank account details. It:

1. seeds the default chart (if there isn't one yet),
2. adds per-flat openings: `MEMBER_DUES` = the flat's maintenance outstanding, `MEMBER_ADVANCE` = its
   unused credit, both at that moment,
3. posts one `OPENING` voucher on `startDate`; any difference goes to `GENERAL_FUND` (shown to the user
   before confirming).

Openings can be corrected (the opening voucher is replaced, audited with a reason) until the first
non-opening voucher exists. This is also how a society's old books are brought in when onboarded.

## Vouchers

| Type | Form the user sees | Lines |
|---|---|---|
| `RECEIPT` | Income: received into (cash/bank), from (payee, optional flat), account(s), amounts | Dr cash/bank · Cr income/fund/liability |
| `PAYMENT` | Expense: paid from (cash/bank), to (payee), account(s), amounts | Dr expense/asset/liability · Cr cash/bank |
| `CONTRA` | Transfer: from cash/bank, to cash/bank, amount | Dr to · Cr from |
| `JOURNAL` | Raw debit/credit lines (secretary only) | any ledgers, must balance |
| `OPENING` | Starting the books | any, balanced via `GENERAL_FUND` |
| `SYSTEM` | Not created by hand: maintenance postings | see below |

- **Numbers** per society, type and financial year: `RV/2026-27/0001`, `PV/…`, `CV/…`, `JV/…`, `SV/…`,
  taken under the society-row lock that maintenance already uses. Unique index on
  (society, type, financial year, number).
- **Fields:** date, narration, payee (free text), optional reference (cheque no., UPI ref, invoice no.).
- **Attachments:** several per voucher (`finance_document`: type INVOICE / RECEIPT / APPROVAL /
  BANK_PROOF / OTHER, original filename, content type, size, uploaded by/at). JPEG, PNG, WebP or PDF, ≤ 5
  MB each, type detected from the bytes (PDF detection added to `common/storage`). Downloads follow the
  voucher's permissions. Attachments can be added to a posted voucher (audited) but not removed from it.
- **Statuses:** `PENDING_APPROVAL → POSTED | REJECTED`, `POSTED → VOID`. `POSTED` and `VOID` vouchers are in the books (a void one stays, and its reversal cancels it out); pending and rejected ones never count.
- **Approval:** a `PAYMENT` or `JOURNAL` above `financeApprovalAbove` is created `PENDING_APPROVAL`. A
  `FINANCE.VOUCHER.APPROVE` holder who is **not** its maker approves (posts it) or rejects it with a
  reason. The maker can edit or withdraw it while it waits (withdrawn ones stay as REJECTED, so no number is lost). Approvers get a push when one is waiting; the
  maker gets one when it's approved or rejected.
- **Posted vouchers are never edited or deleted.** Voiding (reason required) keeps the voucher and books
  a linked reversal on the void date, so earlier reports don't change. `SYSTEM` vouchers can't be voided
  here; undo the action on the maintenance side.
- **Lock date** (`lockedUntil`): no voucher can be dated, approved or voided on or before it. Moving it
  back (unlocking) needs a reason and is audited. Maintenance postings dated inside the locked period are
  booked on the first open day with a note, so maintenance is never blocked by the books.
- Dates can't be before `startDate` or in the future. Ledgers must be active and must not be groups.

## Maintenance: advances, credit and refunds (changes in `maintenance`)

These close the "advance payments" open question in [maintenance.md](maintenance.md). The credit is
**derived**, never a separately editable balance: a flat's credit = the unallocated part of its
non-void payments − its refunds (pending ones included, so pending refunds reserve it).

- **Overpayment.** With `acceptAdvancePayments` (society setting, default on) a payment may exceed what
  the flat owes; the rest stays on the payment unallocated. With it off, it's refused (400) as today.
- **Using credit.** When a cycle (or the opening-balance cycle) is issued, each flat's credit pays its open
  bills oldest due date first, the new ones included, as ordinary allocations of the original payments
  (oldest payment first) marked `fromCredit` with the date they were applied. Bills and receipts show it.
- **Refunds** (`maintenance_refund`): flat, amount (≤ available credit), method, the cash/bank account
  paid from (optional), reference, date, reason, documents. Above `financeApprovalAbove` it waits
  (`PENDING_APPROVAL → COMPLETED | REJECTED`) for a `MAINTENANCE.REFUND.APPROVE` holder who isn't its
  maker; otherwise it's `COMPLETED` at once. It takes credit from the flat's oldest payments first and
  remembers which (`maintenance_refund_allocation`), so the same credit can't be refunded twice.
- **Voiding** a payment also removes the allocations its credit made (those bills reopen). It's refused
  while part of its credit is refunded: void the refund first (the same idea as the waived-bill rule).
- **Voiding a refund** (reason required, `COMPLETED → VOID`) gives the credit back to the flat. A pending
  refund is simply rejected or withdrawn by its maker.
- **Deposit account.** A payment (and a refund) can carry `depositAccountId`. Maintenance stores the
  number without knowing what it is; finance checks it in the same transaction (an active cash/bank ledger
  of that society, else 400 and nothing is saved).
- **Reports.** Dues rows show `credit`; a flat with only credit gets a row. "My dues" shows it too.
- **Cheque bounce** (until V1.1 cheque tracking): void the payment with a reason; charge any bank penalty
  as a separate bill line or journal.

## Postings from maintenance

Maintenance publishes events (new, part of `maintenance`'s API); `finance` listens **in the same
transaction** (`@EventListener`, not after-commit), so a failed posting rolls the maintenance write back.
If the society hasn't started its books, events are ignored. `finance` depends on `maintenance`, never the
reverse.

**Idempotency.** Every `SYSTEM` voucher has a source `(sourceType, sourceId, sourceEvent)` with a unique
index on `(society_id, source_type, source_id, source_event)`. The listener checks first and returns the
existing voucher; the index is the safety net. Each source event happens at most once by design, so the
key never blocks a legitimate posting:

| Source event | Voucher |
|---|---|
| `CYCLE / id / ISSUED` | Dr `MEMBER_DUES` per flat · Cr each charge head's mapped account; late-fee lines Cr `LATE_FEE_INCOME` |
| `CYCLE / id / CREDIT_APPLIED` | Dr `MEMBER_ADVANCE` · Cr `MEMBER_DUES`, per flat whose credit was used at that issue |
| `PAYMENT / id / RECORDED` | Dr deposit account · Cr `MEMBER_DUES` (allocated part) · Cr `MEMBER_ADVANCE` (excess) |
| `PAYMENT / id / VOIDED` | Reversal of the receipt, plus reversal of any credit it had applied (Dr `MEMBER_DUES` · Cr `MEMBER_ADVANCE`) |
| `BILL / id / WAIVED` | Dr `WRITE_OFF` · Cr `MEMBER_DUES` (flat) for the written-off part |
| `CYCLE / id / CANCELLED` | Reversal of its cancelled bills' charges, and of any write-off on them |
| `REFUND / id / COMPLETED` | Dr `MEMBER_ADVANCE` (flat) · Cr paid-from account |
| `REFUND / id / VOIDED` | Reversal of the refund |

**Mappings (dynamic, per society; changes affect future postings only, and are audited):**
- Charge head → credit ledger (`finance_charge_head_account`). Unmapped heads use `MAINTENANCE_INCOME`;
  the mapping screen lists them with a warning, so it's a visible, explicit default. A "Sinking fund" head
  should map to the Sinking fund `FUND` ledger.
- Payment method → default deposit ledger (see Bank accounts).

**Maintenance check** (report): per flat, `MEMBER_DUES` balance vs maintenance outstanding and
`MEMBER_ADVANCE` balance vs credit; plus any maintenance action after the books started that has no
voucher, or more than one. Should always be empty. Postings booked late because of the lock date are
timing differences only: balances still match.

## Reports

All by date range or "as of", vouchers in the books only (`POSTED` and `VOID`, with their reversals), with CSV export; the web app prints them. Dates are
the society's calendar dates (vouchers carry a date, not a time). Each report is computed from the
ledger lines, so totals always reconcile to them.

- **Account ledger:** opening, lines with running balance, closing. For `MEMBER_DUES` / `MEMBER_ADVANCE`,
  optionally one flat (the "member ledger").
- **Day book**, **cash book**, **bank book** (per bank account).
- **Trial balance** as of a date (totals must match).
- **Income & Expenditure** for a range: by group and ledger, surplus or deficit.
- **Receipts & Payments** for a range: opening cash/bank, money in and out, closing cash/bank.
- **Balance sheet** as of a date: assets vs funds + liabilities, with the accumulated surplus shown under
  the General fund (until V1.1 year closing books it there).
- **Resident summary** (setting): per month, income and expense totals by ledger, closing cash + bank.
- **Maintenance check** (above). **Audit log** (below).

## Audit trail

`finance_audit_log`: society, entity type (VOUCHER, ACCOUNT, BANK_ACCOUNT, BOOKS, MAPPING, SETTING,
REFUND, DOCUMENT), entity id, action (CREATE, UPDATE, DELETE, APPROVE, REJECT, VOID, START, LOCK, UNLOCK),
old and new values (JSON, no secrets), performed by/at, reason.

- Written in the same transaction as the change, for everything the review lists: vouchers (create, edit
  while pending, approve, reject, void), chart and bank-account changes, openings, mappings, books start
  and lock date, the three finance settings, refunds, attachments.
- **Insert-only:** a database trigger rejects UPDATE and DELETE. (Society deletion, which cascades, needs
  a deliberate exception; decided in step 1.)
- Kept for as long as the society exists; read with `FINANCE.AUDIT.VIEW`.
- Request id and IP address are left out until the app has request tracing.

## Settings

Society settings catalog, new group "Finance":

| Key | Type | Default | Meaning |
|---|---|---|---|
| `financialYearStartMonth` | INTEGER 1–12 | 4 | Month the financial year starts (numbering, report presets) |
| `financeApprovalAbove` | INTEGER ≥ 0 | 0 | Rupees; expenses, journals and refunds above it need a second person; 0 = never |
| `residentsSeeFinanceSummary` | BOOLEAN | false | Residents can see the monthly summary |

Maintenance group: `acceptAdvancePayments` (BOOLEAN, default true).

Books state (start date, lock date, default accounts per method) lives in `finance_books`, because it
references accounts.

## Permissions

| Code | Meaning | Default role |
|---|---|---|
| `FINANCE.SUMMARY.VIEW` | Resident summary (also needs the setting) | RESIDENT |
| `FINANCE.ACCOUNT.VIEW` | Chart, bank accounts, books state | COMMITTEE |
| `FINANCE.VOUCHER.VIEW` | Vouchers, attachments, ledgers, day/cash/bank book | COMMITTEE |
| `FINANCE.REPORT.VIEW` | TB, I&E, R&P, balance sheet, maintenance check | COMMITTEE |
| `FINANCE.REPORT.EXPORT` | CSV exports | COMMITTEE |
| `FINANCE.AUDIT.VIEW` | Audit log | COMMITTEE |
| `FINANCE.VOUCHER.APPROVE` | Approve / reject pending vouchers | COMMITTEE |
| `FINANCE.VOUCHER.CREATE` | Receipt, payment, contra; attachments | SECRETARY |
| `FINANCE.VOUCHER.UPDATE` | Void, edit pending | SECRETARY |
| `FINANCE.JOURNAL.CREATE` | Raw journal vouchers | SECRETARY |
| `FINANCE.ACCOUNT.UPDATE` | Chart, bank accounts, start books, openings, mappings, lock date | SECRETARY |
| `MAINTENANCE.REFUND.CREATE` | Record a refund of credit | SECRETARY |
| `MAINTENANCE.REFUND.APPROVE` | Approve / reject a pending refund | COMMITTEE |

A treasurer is a custom role. Every query and write is scoped to the society in the path, through the
existing `Access` checks; residents only ever see the summary and their own flat's dues and credit.

## Tables

**V19 (finance):**
- `finance_account` (society, code, name, type, parent_id, is_group, system_key, active; unique
  society+code, unique society+system_key)
- `finance_bank_account` (account_id PK, bank_name, display_name, account_type, last4, ifsc, active)
- `finance_books` (society PK, start_date, locked_until, default ledger per payment method, default
  paying ledger)
- `finance_charge_head_account` (charge_head_id PK, account_id)
- `finance_voucher` (society, type, financial_year, number, voucher_date, status, narration, payee,
  reference, amount, source_type/source_id/source_event, reverses_voucher_id, created/approved/rejected/
  voided by and at, reason; unique numbers; unique source)
- `finance_line` (voucher, account, flat_id null, debit, credit; check exactly one > 0)
- `finance_document` (voucher, storage_key, filename, content_type, size, document_type, uploaded by/at)
- `finance_audit_log` (+ insert-only trigger)
- A **deferred constraint trigger** checks that each voucher's debits equal its credits at commit, so the
  rule holds even if application code is bypassed.
- Amounts `NUMERIC(14,2)` (`BigDecimal` in code, never floating point). Indexes on (society, date),
  (account, date), (account, flat).
- Permission rows and role grants.

**V20 (finance):** `finance_voucher.flat_id` (the flat a receipt came from or a payment went to).

**V21 (maintenance):** payment `deposit_account_id`; allocation `from_credit`, `applied_on`;
`maintenance_refund`, `maintenance_refund_allocation`; refund permissions.

## Tests (checklist, from the review §6)

- **Vouchers:** every voucher balances (also at the DB level); one of debit/credit per line; posted ones
  can't be edited or deleted; void needs a reason and creates a linked reversal; lock date respected by
  date, approval, void and reversal; inactive and group ledgers refused; numbers unique per type and year,
  also under concurrent creation.
- **Maintenance:** one posting per source event, a repeated event returns the existing voucher; void
  reverses the right receipt and credit use; cycle cancel reverses the right lines; unmapped heads use the
  default and are listed; the maintenance check is empty after a long mixed scenario (issue, partial pay,
  overpay, credit use, waive, void, refund, cancel).
- **Credit and refunds:** partial payments and multi-bill allocation; excess stays as credit; credit used
  oldest first at issue; refund ≤ available credit, pending refunds reserve it; no double refund; payment
  void refused while its credit is refunded; voiding a refund restores the credit; `acceptAdvancePayments`
  off refuses overpayment.
- **Access:** society scoping on every endpoint and export; residents see neither other flats nor
  vouchers; maker ≠ approver for vouchers and refunds; audit rows can't be updated or deleted.
- **Reports:** trial balance totals match; balance sheet balances; report totals equal the ledger lines;
  pending/rejected excluded; reversals land in the void date's period; reports for a locked period don't
  change.
- **Reliability:** a failing posting rolls back the maintenance action.

## Built so far

### Step 1: books and chart (2026-11-10)

| Endpoint | Permission | What it does |
|---|---|---|
| `GET /api/societies/{id}/finance/books` | ACCOUNT.VIEW | Started?, chart present?, start/lock dates, opening voucher, default accounts |
| `POST /api/societies/{id}/finance/chart` | ACCOUNT.UPDATE | Seeds the default chart (409 once any account exists) |
| `POST /api/societies/{id}/finance/start` | ACCOUNT.UPDATE | `{startDate, openings:[{accountId, amount}]}`; seeds the chart if missing |
| `GET/PUT /api/societies/{id}/finance/openings` | VIEW / UPDATE | Read the openings; correct them (`startDate?`, `openings`, `reason`) while no other voucher exists |
| `GET /api/societies/{id}/finance/accounts?asOf=` | ACCOUNT.VIEW | Whole chart by code with balances (groups total their children) and bank details |
| `POST /api/societies/{id}/finance/accounts` | ACCOUNT.UPDATE | `{code, name, parentId, group}`; type comes from the parent |
| `PATCH / DELETE /api/finance/accounts/{id}` | ACCOUNT.UPDATE | Rename, recode, move, group ↔ ledger, (de)activate; delete |
| `POST /api/societies/{id}/finance/bank-accounts` | ACCOUNT.UPDATE | `{name, bankName, accountType, last4?, ifsc?, code?}` under "Bank accounts"; code picked after the group's (1121, 1122 …) |
| `PATCH /api/finance/bank-accounts/{id}` | ACCOUNT.UPDATE | Bank details and name/active; `""` clears last4 / IFSC |
| `GET /api/finance/accounts/{id}/ledger?flatId=&from=&to=` | VOUCHER.VIEW | Opening, lines with running balance, totals, closing; default period = current financial year (not before the start) to today |
| `GET /api/societies/{id}/finance/audit?entityType=&entityId=` | AUDIT.VIEW | Audit log, newest first |

Every endpoint needs the permission from a society-wide role (`FinanceGuard`): a flat-tied role never
counts, even a custom one holding a finance code.

Rules as built, beyond the plan:
- **Openings** are in the account's natural sign (negative = the other side, e.g. an overdrawn bank). The
  General fund can't be entered: it is always what balances the rest. Member dues and advances can't be
  entered either; they come from maintenance in step 4. Zero amounts are dropped; an account appears once.
- The opening voucher keeps number `OB/<year>/0001`; correcting the start date renumbers it only if the
  financial year changes.
- **The financial year is fixed once the books start:** changing `financialYearStartMonth` afterwards is
  refused (400), because numbers and report periods depend on it. Finance hears about settings changes
  through the `SocietySettingsChanged` event the `society` module now publishes inside its transaction,
  and audits changes to the three finance settings the same way.
- **Chart:** a group can be deactivated only once everything under it is inactive; an account can be
  reactivated only under an active parent. Deleting is refused for system accounts and roots (400), for
  accounts with entries, children, a charge-head mapping, or that are a default of the books (409).
  Names are unique per society ignoring case; codes are 1–20 letters, digits, `.`, `-`, `/`.
- **Database invariants** (hold even if the app is bypassed): every voucher balances (deferred constraint
  trigger, checked at commit); a line posts to a ledger of its voucher's society, never a group; lines
  name a flat exactly on `MEMBER_DUES` / `MEMBER_ADVANCE`; the audit log refuses UPDATE and DELETE. A
  society with books can't be deleted.

Tests: `FinanceBooksIntegrationTest` (15), `FinancialYearsTest`, `DefaultChartTest`.

### Step 2: vouchers (2026-11-10)

| Endpoint | Permission | What it does |
|---|---|---|
| `POST /api/societies/{id}/finance/receipts` | VOUCHER.CREATE | `{date, depositAccountId, lines:[{accountId, amount}], payee?, flatId?, reference?, narration?}`: Dr cash/bank, Cr each line |
| `POST /api/societies/{id}/finance/payments` | VOUCHER.CREATE | `{date, payingAccountId, lines, payee, flatId?, reference?, narration?}`: Dr each line, Cr cash/bank |
| `POST /api/societies/{id}/finance/contras` | VOUCHER.CREATE | `{date, fromAccountId, toAccountId, amount, …}` between two cash/bank ledgers |
| `POST /api/societies/{id}/finance/journals` | JOURNAL.CREATE | `{date, narration, lines:[{accountId, debit or credit}], reference?}`, at least two lines |
| `PUT /api/finance/payments/{id}`, `PUT /api/finance/journals/{id}` | CREATE (maker only) | Edit while waiting; the date must stay in the same financial year |
| `POST /api/finance/vouchers/{id}/withdraw` | VOUCHER.CREATE (maker only) | `{reason?}`: a waiting voucher becomes REJECTED ("Withdrawn by the maker") |
| `POST /api/finance/vouchers/{id}/approve` · `/reject` | VOUCHER.APPROVE (not the maker) | Reject needs `{reason}` |
| `POST /api/finance/vouchers/{id}/void` | VOUCHER.UPDATE | `{reason}`: linked reversal dated today; the original becomes VOID |
| `GET /api/finance/vouchers/{id}` | VOUCHER.VIEW | Lines with account names, flat, documents, who made/decided/voided it, reversal links |
| `GET /api/societies/{id}/finance/vouchers?type=&status=&from=&to=&accountId=&flatId=&q=` | VOUCHER.VIEW | Newest first; `status=PENDING_APPROVAL` is the approvals queue |
| `GET /api/societies/{id}/finance/day-book?from=&to=` | VOUCHER.VIEW | Everything in the books over the period, oldest first |
| `GET /api/societies/{id}/finance/cash-bank?from=&to=` | VOUCHER.VIEW | Opening, received, paid, closing per cash/bank ledger, with totals |
| `POST /api/finance/vouchers/{id}/documents` (multipart `file`, `documentType`) | VOUCHER.CREATE | JPEG/PNG/WebP/PDF ≤ 5 MB, type read from the bytes |
| `GET` · `DELETE /api/finance/documents/{id}` | VIEW · CREATE | Download (inline, `no-store`); delete only while the voucher waits |
| `PUT /api/societies/{id}/finance/lock` | ACCOUNT.UPDATE | `{lockedUntil?, reason?}`; moving it back or clearing it needs a reason (UNLOCK) |
| `PUT /api/societies/{id}/finance/defaults` | ACCOUNT.UPDATE | The five default cash/bank ledgers (deposit per payment method, paying account) |

The cash book and each bank book are the ledger of that account (`GET /api/finance/accounts/{id}/ledger`).

Rules as built:
- **Cash & bank ledgers** are the ledgers under the "Cash & bank" group (cash, any petty cash, banks).
  Receipts and payments need one of them on the money side and none on the other side (use a transfer).
  Member sub-ledgers can't appear in any hand-made voucher: they belong to maintenance.
- **Dates:** not before the books start (the start day itself is fine), not in the future, and after the
  lock date. Approving re-checks the date, so a waiting voucher inside a newly locked period can't be
  approved until the period is reopened.
- **Approval:** payments and journals strictly above `financeApprovalAbove` wait. Approving re-checks the
  lines (e.g. an account deactivated meanwhile). The maker can't approve or reject their own (403), even
  through a higher role; they withdraw it instead. Approvers (society-wide `FINANCE.VOUCHER.APPROVE`,
  except the maker) get `FINANCE_VOUCHER_NEEDS_APPROVAL`; the maker gets `FINANCE_VOUCHER_APPROVED` or
  `…_REJECTED`. Pushes are after commit and best-effort.
- **Void:** only POSTED receipts, payments, contras and journals that aren't reversals themselves. Opening
  balances are corrected instead (400); maintenance entries are undone in maintenance (400). The reversal
  is the same type, numbered in its own series, may post to accounts deactivated since, and needs today
  to be after the lock date.
- **Numbers** come from the society lock, per type and financial year. Withdrawn and rejected vouchers keep
  theirs, so the series never has gaps.
- **Documents** can be added to waiting and posted vouchers (not rejected ones); `DocumentFormat` in
  `common/storage` adds PDF to the image formats, while visitor photos still accept images only.

Tests: `FinanceVoucherIntegrationTest` (15), `DocumentFormatTest`.

### Step 3: maintenance credit and refunds (2026-11-10)

Built in `maintenance` (V21); the rules and endpoints are in [maintenance.md](maintenance.md) under
"Credit and refunds". Beyond the plan:
- `PaymentCredit`, `CreditAllocator` (pure) and `CreditService` work out and spend credit; credit is a
  query over payments, allocations and refund allocations, never a column.
- Cancelling a cycle gives back the credit its bills used (the credit allocations are removed), instead of
  being blocked by them; bills paid directly still block it.
- A payment can now reach the same bill twice (directly, then from credit after a void reopened the
  bill), so the one-allocation-per-bill constraint was dropped.
- Withdrawn refunds become `REJECTED` (like vouchers); `RF-` numbers never have gaps.
- Events for step 4, published inside the transaction: `MaintenanceCreditApplied`,
  `MaintenanceRefundCompleted`, `MaintenanceRefundVoided`. `depositAccountId` / `paidFromAccountId` are
  stored but not yet checked: that is step 4's job, when finance posts.
- The dues response gains `credit`, and `oldestDueDate` can now be null (credit-only rows): the web app
  must handle that in step 6.
- Postman folder 14: advance payments off/on around the "above what is owed" check, then advance, credit,
  dues with credit, refund (400/403/201), void blocked by refund (409), void refund, void advance.

Tests: `MaintenanceCreditIntegrationTest` (10), `CreditAllocatorTest`, `PaymentAllocatorExcessTest`; two
existing payment tests updated for advances being on by default.

### Step 4: maintenance postings (2026-11-10)

`MaintenancePostings` listens to maintenance's events inside its transaction and posts through
`SystemPoster` (one SYSTEM voucher per source event; unique index as the safety net).

| Event (maintenance) | Source | Voucher |
|---|---|---|
| `MaintenanceBillsIssued` (regular) | `MAINTENANCE_CYCLE/id/ISSUED` | Dr Member dues per flat · Cr each line's ledger: head mapping (default Maintenance charges), late charges → Interest / late charges |
| `MaintenanceBillsIssued` (opening balances, `opening = true`) | same | Dr Member dues · Cr General fund (old dues the society already had) |
| `MaintenanceCreditApplied` | `…/CREDIT_APPLIED` | Dr Member advance · Cr Member dues, per flat |
| `MaintenancePaymentRecorded` | `MAINTENANCE_PAYMENT/id/RECORDED` | Dr deposit ledger · Cr Member dues (allocated) · Cr Member advance (excess) |
| `MaintenancePaymentVoided` | `…/VOIDED` | Cr the ledger it went into · Dr Member dues (everything on bills, credit use included) · Dr Member advance (unused rest) |
| `MaintenanceBillWaived` | `MAINTENANCE_BILL/id/WAIVED` | Dr Maintenance waived · Cr Member dues |
| `MaintenanceCycleCancelled` | `MAINTENANCE_CYCLE/id/CANCELLED` | Cr Member dues / Dr income per bill; waivers undone; released credit Dr dues · Cr advance |
| `MaintenanceRefundCompleted` / `…Voided` | `MAINTENANCE_REFUND/id/COMPLETED` / `VOIDED` | Dr Member advance · Cr paid-from ledger (reversed on void) |

Decisions while building:
- **State-based reversals.** A void or cancel is worked out from the payment or cycle as it stands, not by
  reversing an earlier voucher. That way it stays right for things that happened before the books
  started (the opening took them over). Where the original was never booked, its income or write-off side
  goes to the General fund.
- **Head mapping by name.** Bill lines copy the head's name at issue; the posting runs in the same
  transaction, so names still match the heads exactly.
- **Posting dates** are the event's day (period start, paid-on, today for voids, waivers and cancels),
  never after today, never before the books start, and moved to the first open day (noted in the
  narration) inside a locked period.
- **Who:** the signed-in user of the maintenance request; the person who started the books if there is
  none (e.g. background jobs).
- **Deposit ledgers:** an explicit `depositAccountId` / `paidFromAccountId` must be an active cash/bank
  ledger (400 otherwise, even before the books start); without one, the method's default, then the cash
  default, then Cash in hand. Refunds default to the paying account.
- **Opening member balances.** Starting (or correcting) the books now adds one Member dues line per flat
  that owes (open bills' outstanding) and one Member advance line per flat with credit. Credit held by
  waiting refunds counts as advance: the books only see a refund once it's completed. The openings
  response shows `memberDues` and `memberAdvances` totals.
- **Maintenance check** (`GET /api/societies/{id}/finance/maintenance-check`, REPORT.VIEW): per flat,
  all-time member dues and advance in the books vs maintenance; lists only disagreements (`ok` when none).
  It compares balances; it doesn't separately list actions without a voucher (a missing posting shows up
  as a difference).
- **Charge-head mapping** (`GET`/`PUT /api/societies/{id}/finance/charge-head-accounts`): income, fund or
  liability ledgers only (not cash/bank or member accounts); `accountId: null` goes back to the default;
  audited as MAPPING.
- Opening-balance cycles now publish `MaintenanceBillsIssued` too (no push to residents).

Tests: `FinanceMaintenanceIntegrationTest` (12, each ending with a clean maintenance check).

### Step 5: statements (2026-11-10)

All computed from the voucher lines in the books (POSTED and VOID with their reversals); zero rows are
left out; periods default to the current financial year (not before the books start) to today, "as of"
to today. COMMITTEE and above read them (`FINANCE.REPORT.VIEW`) and export them (`FINANCE.REPORT.EXPORT`).

| Endpoint | What |
|---|---|
| `GET …/finance/reports/trial-balance?asOf=` | Every ledger's balance on its own side; `balanced` |
| `GET …/finance/reports/income-expenditure?from=&to=` | Income and expenditure by top-level group (e.g. Utilities, Services), `surplus` (negative = deficit) |
| `GET …/finance/reports/receipts-payments?from=&to=` | Cash and bank at the start, money in and out by the account on the other side, cash and bank at the end |
| `GET …/finance/reports/balance-sheet?asOf=` | Assets vs liabilities + funds; the accumulated surplus is shown with the funds; `balanced` |
| `GET …/finance/summary?from=&to=` | Monthly totals by group and month-end cash + bank (at most 36 months) |
| `…/reports/{trial-balance,income-expenditure,receipts-payments,balance-sheet}/export` | CSV of each |
| `GET /api/finance/accounts/{id}/ledger/export`, `GET …/finance/day-book/export` | CSV ledger; CSV day book (one row per line, at most 5000 entries) |

Decisions while building:
- **Groups** are the child of the type's root that holds a ledger ("Utilities", "Services" …); a ledger
  directly under the root is its own group.
- **Receipts & Payments** counts only vouchers that touch a cash/bank ledger, nets each other account over
  the period and puts it on the side of its net movement, so transfers between cash and bank never show.
  The opening voucher is treated as the opening balance even when the period starts on the books' first
  day.
- **Balance sheet:** until year closing exists (V1.1), the surplus to date is computed and shown with the
  funds, so it always balances; nothing is posted for it.
- **Resident summary** (`FINANCE.SUMMARY.VIEW`, RESIDENT): society-wide report readers always; residents only
  while `residentsSeeFinanceSummary` is on (403 otherwise). It holds group totals and cash + bank, never an
  entry, payee or flat.
- **Postman:** folder 17 "Finance" (cleanup is now 18): chart, bank, start, receipt, approval (the super
  admin as second person), transfer, void, day book, cash & bank, ledger, the four statements,
  maintenance check, CSV, resident 403s, audit log. Full run: 238 requests, 337 assertions, one failure
  that predates finance (cleanup can't delete the test resident: `invitation_invited_by_fkey`).

Tests: `FinanceStatementsIntegrationTest` (6, one worked example with totals checked by hand).

## Build steps

1. **Books and chart:** `finance` module, V19, `ModuleDependencyTest` entry (may use `common`,
   `authorization`, `society`, `user`, `notification`, `maintenance`), settings group, hierarchy, bank
   accounts, start books with openings, audit trail, account ledger.
2. **Vouchers:** receipt/payment/contra/journal, numbering, approval with pushes, void by reversal, lock
   date, attachments (PDF detection in `common/storage`), day/cash/bank book.
3. **Maintenance credit and refunds:** V21, overpayment as credit, credit use at issue, refunds with
   approval, deposit account, dues/receipt changes; update maintenance.md and the Postman maintenance
   folder.
4. **Maintenance postings:** events, idempotent same-transaction listeners, mappings, per-flat openings,
   maintenance check.
5. **Statements:** trial balance, I&E, R&P, balance sheet, resident summary, audit log view, CSV; Postman
   finance folder.
6. **Web:** Finance section: setup wizard (start date, banks, openings, mappings), chart, Income / Expense /
   Transfer forms, approvals queue, vouchers with attachments, ledgers and member ledger, statements with
   print, audit log, resident summary card; maintenance: credit on dues and bills, refunds.

Each step ends with the suite passing and its own commit.

**V1.1 next:** bank statement import (CSV) and reconciliation (planned in [finance-reconciliation.md](finance-reconciliation.md)) (`cleared_on` per bank line, sessions that
are never allowed to edit vouchers), year closing (closing voucher moving the surplus to the General
fund, then lock), recurring expense drafts, cheque lifecycle (with a bounce flow), petty cash counts, the
remaining notifications.

## Needs an accountant (before commercial use)

- Treatment of each fund: is the sinking fund credited straight from the bill (the plan's default
  mapping) and how is interest on fund investments allocated?
- Waivers: an expense (`WRITE_OFF`, the plan) or a reduction of income?
- Year-end closing entries and how the accumulated surplus is shown.
- The I&E and balance-sheet layout the society's auditor expects (ask the client's auditor for a sample).
- Whether late-payment interest is income when billed (the plan) or when received.

## Answers to the review's open decisions (§9)

| Question | Answer in this plan |
|---|---|
| Advance receipts | Liability `MEMBER_ADVANCE`, used at the next issue, oldest bill first (decision 5) |
| Excess payments and refunds | Excess becomes credit; only credit can be refunded, with approval above the threshold (decision 6) |
| Fund treatment | Mapping per charge head; exact treatment → accountant |
| Year-end closing | V1.1 closing voucher; exact treatment → accountant |
| Multiple banks in V1 | Yes |
| Clearing accounts | None in V1; considered with cheque tracking in V1.1 |
| Who prepares / approves / voids / locks | Permissions table above; maker ≠ approver |
| Audit / document retention | Kept as long as the society exists; insert-only |
| Unmatched bank lines | V1.1 reconciliation |
| Migrating old balances | Start books with openings |
| Auditor's formats | Open: ask the client's auditor |
