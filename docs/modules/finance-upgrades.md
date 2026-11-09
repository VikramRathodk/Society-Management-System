# Finance & Accounting Module — Recommended Upgrades

**Based on:** [`finance.md`](finance.md)
**Review date:** 2026-11-09
**Purpose:** Extend the existing Finance & Accounting specification without replacing its current
architecture.

> This document lists recommended upgrades to the existing Finance & Accounting module. Existing design
> decisions should be retained unless explicitly noted below. Statutory accounting treatment must be
> validated by a practising Maharashtra cooperative housing society accountant before commercial release.

**How this review was applied (2026-11-09):** see "Review outcome" at the end of this file. What was
adopted is now part of [`finance.md`](finance.md); that file is the plan to build from.

---

## 1. Summary of Recommended Upgrades

| # | Upgrade | Priority | Suggested release |
|---|---|---|---|
| 1 | Financial audit trail | Critical | V1 |
| 2 | Advance maintenance payment handling | Critical | V1 |
| 3 | Refund and excess-payment handling | Critical | V1 |
| 4 | Idempotency and duplicate-posting prevention | Critical | V1 |
| 5 | Multiple bank accounts | High | V1 |
| 6 | Bank reconciliation | High | V1.1 |
| 7 | Financial year closing and opening balances | High | V1.1 |
| 8 | Recurring expense templates | Medium | V1.1 |
| 9 | Cheque lifecycle tracking | Medium | V1.1 |
| 10 | Multiple voucher attachments | Medium | V1.1 |
| 11 | Account hierarchy and validation | Medium | V1 |
| 12 | Tax-ready data model (GST/TDS) | Medium | Later |
| 13 | Investment / FD register | Medium | Later |
| 14 | Petty cash controls | Medium | V1.1 |
| 15 | Financial notifications and reminders | Medium | V1.1 |

---

## 2. Critical Upgrades — V1

### 2.1 Financial Audit Trail

Add an immutable audit history for sensitive financial actions and configuration changes.

**Track at least:**
- Voucher created, edited while pending, approved, rejected, and voided.
- Chart-of-accounts created, updated, deactivated, or reconfigured.
- Opening balances created or corrected.
- Charge-head-to-account mappings changed.
- Payment-method-to-bank-account mappings changed.
- Expense approval threshold changed.
- Books started and accounting lock date changed.
- Reconciliation actions, when that feature is introduced.

**Suggested table: `finance_audit_log`**

| Field | Description |
|---|---|
| `id` | Primary key |
| `society_id` | Owning society |
| `entity_type` | VOUCHER, ACCOUNT, BOOKS, SETTINGS, MAPPING |
| `entity_id` | Affected record identifier |
| `action` | CREATE, UPDATE, APPROVE, REJECT, VOID, LOCK, UNLOCK |
| `old_values` | Previous values; nullable for create |
| `new_values` | New values; nullable where not applicable |
| `performed_by` | User who performed the action |
| `performed_at` | Timestamp |
| `reason` | Reason for sensitive changes, where required |
| `request_id` | Correlation identifier for tracing |
| `ip_address` | Optional security metadata |

**Rules:**
- Ordinary application users must not be able to edit or delete audit records.
- Record sensitive changes in the same database transaction as the business action where possible.
- Do not store passwords, access tokens, or other secrets in audit payloads.
- Define retention and access policies for audit records.

### 2.2 Advance Maintenance Payments

Support residents who pay more than the amount currently due or pay several months in advance.

**Example:** A resident pays ₹30,000 in advance. The amount received is initially recorded as a
liability, not immediately recognized as maintenance income for future periods.

Illustrative accounting entry:

| Account | Debit | Credit |
|---|---:|---:|
| Bank | ₹30,000 | — |
| Advance from Members | — | ₹30,000 |

When a bill becomes due, allocate the relevant advance against the resident's receivable according to
the configured accounting policy.

**Required capabilities:**
- Maintain advance balance per flat/member.
- Allocate advance fully or partially to one or more bills.
- Prevent allocation beyond available advance or bill balance.
- Support unallocated advance balance.
- Show advance history in the member ledger.
- Handle transfer of ownership and adjustment/refund scenarios through an authorized workflow.
- Maintain a clear distinction between bill receivable, payment received, and advance liability.

**Suggested data model additions:**
- `finance_member_advance` or an equivalent liability-ledger approach.
- `finance_advance_allocation` to link advance amounts to bills.
- Prefer the accounting ledger as the source of truth; avoid maintaining a separately editable balance
  that can drift from journal entries.

### 2.3 Refund and Excess-Payment Handling

Distinguish payment correction, payment reversal, excess payment, and an actual refund.

| Scenario | Expected treatment |
|---|---|
| Incorrect payment entry | Void/reverse the original receipt with reason |
| Duplicate payment entry | Reverse the duplicate receipt |
| Resident pays more than the bill | Keep the excess as member advance or unallocated credit |
| Society returns money to resident | Record an authorized refund/payment voucher |
| Online payment fails | Do not record a successful receipt |
| Cheque is dishonoured | Reverse the receipt and record applicable charges separately, where permitted |

**Required capabilities:**
- Refund request and approval, if required by society policy.
- Refund amount cannot exceed the eligible credit/advance balance.
- Link refund to the original receipt and member/flat.
- Capture refund method, bank reference, date, reason, and supporting document.
- Preserve the original receipt and refund history.
- Prevent a refund from being processed twice.

### 2.4 Idempotency and Duplicate-Posting Prevention

Automatic postings from Maintenance must not create duplicate vouchers if an event is retried or
delivered more than once.

**Required controls:**
- Define a stable source-event identity for every automatic accounting event.
- Add a database uniqueness constraint for the source event identity.
- Make event handling idempotent.
- Use an idempotency key for payment gateway callbacks and other retryable external requests.
- Ensure duplicate requests return the existing result rather than posting again.
- Test concurrent duplicate requests.
- Keep the maintenance operation and its accounting posting atomic where the existing same-transaction
  event design is used.

**Suggested fields on system vouchers:** `source_type`, `source_id`, `source_event`, `idempotency_key`
(where applicable).

A possible uniqueness rule is `UNIQUE (society_id, source_type, source_id, source_event)`. Confirm that
the chosen source identity supports legitimate multiple events for the same source record.

### 2.5 Multiple Bank Accounts

The existing specification has a default `BANK` system account. Extend the model to support multiple
bank accounts without changing the double-entry foundation.

**Required capabilities:**
- Multiple society bank accounts.
- Bank name, account display name, masked account number, IFSC, and account type.
- Link each bank account to its own asset ledger account.
- Configure default receiving and payment accounts.
- Select the actual bank account on receipt, payment, and contra vouchers.
- Restrict sensitive bank details by permission.
- Deactivate an account without deleting historical transactions.
- Maintain opening balance and reconciliation state per bank account.

Do not store full bank credentials, net-banking passwords, or card PINs.

### 2.6 Account Hierarchy and Accounting Validation

Consider supporting parent and child account groups rather than only one grouping level.

**Requirements:**
- Parent account / group relationship.
- Prevent posting directly to non-posting group accounts.
- Prevent circular account hierarchy.
- Preserve account type and system-account protections.
- Validate that inactive accounts cannot receive new postings.
- Validate that system accounts cannot be deleted, retyped, or deactivated.
- Provide a clear account-code and naming convention.

---

## 3. Important Operational Upgrades — V1.1

### 3.1 Bank Reconciliation

Implement reconciliation between the accounting bank ledger and the actual bank statement.

**Workflow:**
1. Select society bank account and statement period.
2. Import bank statement (CSV initially).
3. Parse and validate statement rows.
4. Match statement rows with posted ledger transactions.
5. Mark matched, unmatched, and partially matched entries.
6. Investigate differences.
7. Record missing bank charges or other valid transactions through approved vouchers.
8. Complete reconciliation and retain the reconciliation snapshot.

**Suggested tables:** `finance_reconciliation`, `finance_reconciliation_line`,
`finance_bank_statement_import`.

**Suggested reconciliation fields:**
- Society and bank account.
- Statement start and end dates.
- Statement opening and closing balances.
- Book balance at reconciliation date.
- Reconciled difference.
- Status: DRAFT, IN_PROGRESS, COMPLETED, REOPENED (if policy permits).
- Prepared by, completed by, timestamps, and reason.

**Important rules:**
- Reconciliation must not silently edit or delete posted vouchers.
- Corrections must be made through authorized accounting entries.
- Preserve imported statement source and import history.
- Prevent the same statement transaction from being imported and posted twice.
- Define who can complete or reopen a reconciliation.

### 3.2 Financial Year Closing

The existing document uses an April–March financial year. Add an explicit year-end workflow.

**Required capabilities:**
- Review and validate all ledgers before closing.
- Verify Trial Balance totals.
- Review outstanding member dues, advances, vendor balances, bank balances, and fund balances.
- Close income and expense accounts using the accounting treatment approved for the society.
- Carry forward balance-sheet accounts to the next financial year.
- Preserve previous-year reports and voucher history.
- Create and verify opening balances for the new financial year.
- Prevent ordinary users from changing closed-period records.
- Support authorized corrections through controlled adjustment/reopening procedures.

**Important:** The exact closing entries and presentation of accumulated surplus/deficit must be
validated against the applicable Maharashtra cooperative housing society accounting framework. The
current proposal to compute surplus in reports without closing entries should be reviewed before
statutory use.

### 3.3 Recurring Expense Templates

Support recurring expense drafts for common society expenses, e.g. security agency, housekeeping, lift
AMC, generator maintenance, insurance, periodic inspections.

**Capabilities:**
- Frequency: monthly, quarterly, half-yearly, yearly, or custom.
- Start and end dates.
- Vendor/payee reference.
- Expense account.
- Expected amount (fixed or variable).
- Due date and reminder.
- Attachment/reference to contract or invoice.
- Generate a draft voucher for review.

Recurring templates must not automatically imply approval or payment. The generated voucher should
follow the normal approval and posting workflow.

**Suggested table:** `finance_recurring_template`

### 3.4 Cheque Lifecycle Tracking

Track cheque-related transactions where societies still use cheques.

**Suggested statuses:** ISSUED, DEPOSITED, CLEARED, BOUNCED, CANCELLED.

Capture cheque number, bank, cheque date, deposit date, clearing date, payee/payer, amount, and linked
voucher.

A cheque's accounting treatment should reflect the society's accounting policy and actual clearing
status. Avoid treating an uncleared cheque as cleared bank cash.

### 3.5 Multiple Voucher Attachments

The current design allows one attachment per voucher. Consider replacing `attachment_key` with a
separate document relation.

**Suggested table:** `finance_document` with `id`, `society_id`, `voucher_id`, `storage_key`,
`original_filename`, `content_type`, `file_size`, `uploaded_by`, `uploaded_at`, `document_type`.

Allow invoices, receipts, approvals, bank proofs, and supporting documents to be attached to a voucher.
Enforce file size/type validation, secure access, and malware/content validation appropriate to the
storage pipeline.

### 3.6 Petty Cash Management

Add controls for small cash expenses:
- Cash-in-hand balance.
- Petty cash replenishment.
- Expense vouchers.
- Supporting receipts.
- Cash count / physical verification.
- Cash balance discrepancy reporting.

The cash ledger should remain the accounting source of truth.

### 3.7 Financial Notifications

Potential notifications:
- Expense waiting for approval.
- Expense approved or rejected.
- Reconciliation difference remains unresolved.
- Recurring expense draft generated.
- Voucher missing supporting document.
- Financial period approaching lock/close.
- Resident payment receipt generated.

Notifications must not expose private financial details to unauthorized users.

---

## 4. Later Enhancements

### 4.1 GST and TDS Readiness

GST and TDS are deferred in the current specification. Keep the data model extensible for:
- Vendor GSTIN and tax registration details, where applicable.
- Taxable amount and tax components.
- TDS section, rate, amount, and deduction date, where applicable.
- Tax document references.
- Tax reporting and reconciliation.

Do not assume every society transaction is taxable or that every society must deduct TDS.
Applicability depends on the transaction and current law. Validate requirements with a qualified
accountant/tax professional.

### 4.2 Fixed Assets and Depreciation

Coordinate with the planned Asset module. Potential capabilities:
- Asset purchase linked to a vendor invoice and payment voucher.
- Asset category and capitalization date.
- Cost and useful life.
- Depreciation method and rate.
- Accumulated depreciation.
- Disposal/write-off.
- Asset register and depreciation reports.

Avoid duplicating the Asset module's ownership of asset records.

### 4.3 Investments and Fixed Deposits

Add an investment register for society FDs and other permitted investments. Potential fields:
financial institution, principal amount, investment and maturity date, interest rate and payout type,
linked bank account, certificate/reference number, renewal or maturity instructions, interest received
and accrued, TDS where applicable.

Accounting should distinguish principal, accrued interest, interest received, and maturity proceeds.
Validate the treatment with the society accountant.

### 4.4 Budgets and Budget-vs-Actual

The current specification defers budgets. Later add:
- Annual budget by account.
- Budget approval and version history.
- Monthly/quarterly allocation.
- Actual versus budget.
- Variance amount and percentage.
- Explanation for material variances.

---

## 5. Cross-Module Integration Requirements

Finance should be the central accounting source of truth, while operational modules remain responsible
for their own business records.

| Source module | Business event | Finance responsibility |
|---|---|---|
| Maintenance | Bill/cycle issued | Record member receivable and mapped income/fund |
| Maintenance | Payment recorded | Record receipt and reduce member receivable |
| Maintenance | Payment reversed | Reverse linked accounting posting |
| Maintenance | Bill waived | Record approved write-off treatment |
| Payment | Gateway callback confirmed | Record receipt once, using idempotency |
| Vendor | Invoice approved | Record expense/asset and payable, according to policy |
| Vendor | Vendor paid | Record payment and clear payable |
| Facility / Booking | Charge becomes recognized | Record mapped income/receivable |
| Asset | Asset purchased | Record asset acquisition |
| Investment | FD created/matured | Record investment movement and related income |

**Integration principles:**
- Each module owns its operational records.
- Finance owns accounting vouchers and ledger balances.
- Every automatic posting must have a traceable source reference.
- Avoid circular module dependencies.
- Use transactional events or a reliable outbox/inbox pattern as appropriate to the actual transaction
  boundary.
- Reconciliation checks should detect missing or duplicate postings.

---

## 6. Additional Business Rules and Validation Tests

Add automated tests for the following cases.

### Voucher and ledger rules
- Every posted voucher balances: total debit equals total credit.
- Exactly one of debit or credit is positive on each line.
- Amounts use fixed-precision decimal types; never floating-point arithmetic.
- Posted vouchers cannot be edited or deleted.
- Void creates a linked reversal and requires a reason.
- Reversal date respects the accounting lock date.
- Inactive accounts cannot receive new postings.
- Voucher numbers are unique per society, voucher type, and financial year.

### Maintenance integration rules
- One source event creates at most one accounting posting.
- Retried events do not duplicate postings.
- Payment reversal links to the original receipt posting.
- Bill cancellation reverses the correct original entries.
- Charge-head mappings are validated.
- Unmapped charge heads are visible and handled according to an explicit policy.
- Member-dues ledger agrees with Maintenance outstanding, with documented treatment for advances,
  waivers, and timing differences.

### Payment and refund rules
- Partial payments allocate correctly.
- One payment can be allocated across multiple bills if supported.
- Excess payments remain as member credit/advance.
- Refund cannot exceed eligible available credit.
- Duplicate payment callbacks do not create duplicate receipts.
- Failed payment callbacks do not post successful receipts.
- Cheque bounce treatment reverses the appropriate receipt.

### Access-control rules
- Every query and mutation is scoped to the authenticated society.
- Residents cannot view other residents' ledgers or vouchers.
- Committee/treasurer permissions are explicit and configurable.
- Maker and approver separation is enforced where required.
- Audit history is not editable by ordinary users.
- Exports follow the same authorization and society-scoping rules as reports.

### Reporting rules
- Trial Balance debit and credit totals match.
- Balance Sheet balances.
- Report totals reconcile to the underlying posted ledger lines.
- Date filters use the society's accounting date/time policy.
- Reports exclude pending and rejected vouchers.
- Reversal entries appear in the correct accounting period.
- Closed-period reports remain reproducible.

### Concurrency and reliability
- Concurrent voucher creation does not duplicate voucher numbers.
- Concurrent payment callbacks do not duplicate receipts.
- Failed maintenance-to-finance posting rolls back or is reliably retried according to the chosen
  architecture.
- Database constraints protect critical invariants even if application validation is bypassed.

---

## 7. Suggested Updated Database Additions

These are candidate additions, not a mandate to create every table in V1.

| Table / model | Purpose | Suggested release |
|---|---|---|
| `finance_audit_log` | Immutable financial audit history | V1 |
| `finance_member_advance` | Advance balance view/record, if needed beyond ledger | V1 |
| `finance_advance_allocation` | Link advance to bills | V1 |
| `finance_refund` | Refund workflow and source linkage | V1 |
| `finance_bank_account` | Multiple bank accounts and metadata | V1 |
| `finance_reconciliation` | Reconciliation session | V1.1 |
| `finance_reconciliation_line` | Statement-to-ledger matching | V1.1 |
| `finance_bank_statement_import` | Statement import history | V1.1 |
| `finance_recurring_template` | Recurring expense configuration | V1.1 |
| `finance_document` | Multiple voucher attachments | V1.1 |
| `finance_year_closing` | Closing workflow and snapshots | V1.1 |
| `finance_cheque` | Cheque lifecycle tracking | V1.1 |
| `finance_investment` | FD/investment register | Later |
| `finance_tax_detail` | Tax metadata | Later |

Do not duplicate balances that can be derived from journal lines unless there is a clear performance or
workflow reason. If a cached balance is introduced, define how it is rebuilt and reconciled.

---

## 8. Recommended Revised Implementation Plan

### V1 — Reliable Accounting Foundation
1. Chart of accounts and account hierarchy.
2. Start books and opening balance validation.
3. Receipt, Payment, Contra, Journal, Opening, and System vouchers.
4. Voucher numbering and concurrency protection.
5. Approval, rejection, void-by-reversal, and lock-date rules.
6. Financial audit trail.
7. Multiple bank accounts.
8. Advance payment and member-credit handling.
9. Refund workflow.
10. Idempotent maintenance accounting postings.
11. Ledger, Day Book, Cash Book, Bank Book, Trial Balance.
12. Income & Expenditure, Receipts & Payments, Balance Sheet.
13. Maintenance-to-finance reconciliation/check report.
14. Automated accounting and security tests.

### V1.1 — Operational Controls
1. Bank statement import.
2. Bank reconciliation.
3. Recurring expense templates.
4. Cheque lifecycle tracking.
5. Multiple voucher attachments.
6. Petty cash controls.
7. Financial notifications.
8. Financial year closing workflow.
9. Budget-vs-actual foundations.

### V2 — Advanced Accounting
1. GST/TDS support, subject to applicability.
2. Fixed assets and depreciation integration.
3. FD and investment register.
4. Advanced audit/document workflows.
5. Advanced financial analytics.

---

## 9. Decisions to Confirm Before Implementation

Resolve these with the product owner and, where accounting treatment is involved, a qualified society
accountant.

- [ ] How are advance maintenance receipts recognized and allocated?
- [ ] How are excess payments and resident refunds handled?
- [ ] What is the approved accounting treatment for each society fund?
- [ ] What is the exact year-end closing treatment?
- [ ] Will each society have multiple bank accounts in V1?
- [ ] Which payment methods require clearing/settlement accounts?
- [ ] Who can prepare, approve, void, lock, and reopen financial records?
- [ ] What is the retention policy for financial audit logs and documents?
- [ ] What is the policy for unmatched bank statement transactions?
- [ ] How are old accounting balances migrated when a society is onboarded?
- [ ] What reports and formats does the society's auditor require?

---

## 10. Final Recommendation

Keep the existing double-entry architecture, automatic maintenance postings, immutable posted vouchers,
reversal-based corrections, and role-based access.

Before implementation, prioritize these five upgrades:
1. **Audit trail** — financial accountability.
2. **Advance payments and refunds** — correct member balances.
3. **Idempotency** — prevent duplicate accounting entries.
4. **Multiple bank accounts** — represent real society banking.
5. **Bank reconciliation design** — ensure books agree with bank statements.

Treat statutory fund classification, year-end closing, GST/TDS, and financial statement presentation as
accounting-policy decisions that must be verified before commercial use.

The objective is a reliable society accounting system in which every financial amount can be traced
from its source transaction to its voucher, ledger, bank movement, and financial report.

---

## Review outcome (2026-11-09)

Each suggestion checked against the plan and the existing code. **Adopted** items are now in
[`finance.md`](finance.md).

| # | Suggestion | Outcome | Why |
|---|---|---|---|
| 1 | Audit trail | **Adopted, V1** | `finance_audit_log`, written in the same transaction, insert-only (a DB trigger refuses UPDATE/DELETE). `request_id` and `ip_address` left out: the app has no request-id tracing yet; add both when it does. |
| 2 | Advance payments | **Adopted, V1** | Closes the open "advance payments" question in maintenance.md. The credit is derived (payment amount − its allocations), not a separately editable balance. It lives in `maintenance`, because that's where bills are allocated; finance books it to an "Advance from members" liability. |
| 3 | Refunds / excess payments | **Adopted, V1** (refund of unused credit) | Wrong or duplicate entries are already handled by void. Online-payment failures belong to the Payment module (16), which doesn't exist yet. Cheque bounce goes in V1.1 with cheque tracking; until then, void with a reason. |
| 4 | Idempotency | **Adopted, V1** | Unique `(society_id, source_type, source_id, source_event)` plus a check-then-insert in the listener. The posting runs in the same transaction, so retries are unlikely; the constraint is the safety net. Gateway idempotency keys arrive with module 16. |
| 5 | Multiple bank accounts | **Adopted, V1** | `finance_bank_account` 1:1 with an ASSET account. Only the last 4 digits of the account number are stored (plus IFSC), so there is nothing sensitive to hide. A maintenance payment can name the bank account it went into. |
| 6 | Bank reconciliation | **V1.1, as suggested** | Planned in finance.md's V1.1 list. V1 keeps the voucher's bank lines so a later `cleared_on` needs no data migration. |
| 7 | Year closing | **V1.1, and the review's warning is noted** | V1 has a lock date. V1.1 adds an explicit closing voucher that moves the year's surplus/deficit to the General fund, so reports don't depend on computing it. The exact treatment is flagged for the society's accountant. |
| 8 | Recurring templates | V1.1 | As suggested; drafts follow normal approval. |
| 9 | Cheque lifecycle | V1.1 | As suggested. |
| 10 | Multiple attachments | **Brought forward to V1** | Cheaper to build `finance_document` now than to migrate `attachment_key` later. |
| 11 | Account hierarchy | **Adopted, V1** | Group accounts (non-posting) with `parent_id`, any depth, no cycles. Replaces the one-level `group_name`. |
| 12 | GST/TDS | Later | As suggested. Nothing in V1 blocks adding tax lines. |
| 13 | FD register | Later | Until then an FD is an ASSET account and interest is a RECEIPT. |
| 14 | Petty cash | V1.1 | V1 can already use a separate "Petty cash" ASSET account with Contra top-ups; V1.1 adds cash counts. |
| 15 | Notifications | **Partly V1** | Approval pushes (waiting / approved / rejected) are cheap and go in V1. The rest go in V1.1. |
| §5 | Other modules (Vendor, Facility, Asset, Payment) | Noted | Each module publishes events and finance listens, as maintenance does. Built when those modules are. |
| §6 | Test list | **Adopted** | It's the test checklist in finance.md. Balanced vouchers are also enforced by a deferred DB constraint trigger. |
| §9 | Decisions | Partly answered | See "Decisions" and "Needs an accountant" in finance.md. |
