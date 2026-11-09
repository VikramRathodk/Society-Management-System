# Maintenance Management (`maintenance`)

Society maintenance dues: what each flat is charged, billing it per period, recording payments, and
who hasn't paid. Module 14 in [MODULES.md](../MODULES.md).

**Status:** ✅ M7a (configuration + billing), M7b (payments, late fees, opening balances, reports) and M7c
(web screens, Postman folder) built 2026-11-01. Open decision: advance payments (see Follow-ups).

## Scope

| In | Later / elsewhere |
|---|---|
| Per-society settings and charge heads | Online payment gateway — Payment module (16) |
| Billing cycles: preview, issue, cancel | Income/expense ledger — Finance module (15) |
| Bills per flat with frozen line items; waive | GST on maintenance |
| Recording offline payments, receipts, void | Charges by flat type (1BHK/2BHK) |
| Dues, defaulters and collection reports, CSV | Non-occupancy charges for tenant flats |
| Late fee / interest rules | Advance payments (credit carried to later bills) |
| Opening balances for existing societies | |

## Configuration (dynamic, per society)

Changing configuration only affects cycles issued afterwards; an issued bill keeps its own copy of the
charges.

**Settings** (`maintenance_settings`, defaults when never saved):

| Setting | Default | Meaning |
|---|---|---|
| `billingFrequency` | `MONTHLY` | `MONTHLY`, `QUARTERLY`, `HALF_YEARLY`, `YEARLY`: length of the suggested next period |
| `dueDays` | 15 | Suggested due date = period start + this many days (0–365) |
| `roundToRupee` | true | Round each bill line to whole rupees (half up); false keeps paise |
| `lateFeeType` | `NONE` | `NONE`, `FIXED` (`lateFeeValue` rupees, once per overdue bill), `SIMPLE_INTEREST` (`lateFeeValue` % a year, 0–100) |
| `lateFeeValue` | 0 | Rupees for FIXED, annual % for SIMPLE_INTEREST; must be > 0 unless NONE |
| `graceDays` | 0 | A bill is overdue only once this many days have passed after its due date (0–365) |
| `upiId` | none | The society's UPI ID (`handle@provider`). When set, unpaid bills show a pay-by-UPI QR code (V15) |
| `upiPayeeName` | none | Name UPI apps show the payer (≤ 100); the web app falls back to the society name |

The late-fee and UPI fields are optional on `PUT`: leaving them out keeps the saved values; for the UPI
fields an empty string removes the saved value.

**Pay by UPI.** The web app turns the UPI ID into a standard `upi://pay` QR code (payee, amount still
owed, note "Maintenance <bill no.> <flat>") on the bill page and the printed bill; on a phone, "Open UPI
app" opens it directly. The QR is drawn in the browser (`qrcode` package), nothing is sent to a third
party. This is not a payment gateway: the app isn't told about the payment, so the resident shares the
UPI transaction ID and the committee records it as before (method UPI, reference = transaction ID).

**Charge heads** (`maintenance_charge_head`): the items on every bill, e.g. Maintenance, Sinking fund,
Parking. Each has `calcType` `FIXED` (amount per flat) or `PER_SQFT` (amount × the flat's `areaSqft`),
an `active` flag and a `sortOrder` for the bill. Names are unique per society (case-insensitive).
Deleting a head is always safe because issued bills copied it.

## Billing

1. `GET .../cycles/next` suggests the period after the latest issued cycle (or the current month).
2. `POST .../cycles/preview` returns every flat's bill and a `problems` list (e.g. a flat with no area
   while a per-sq-ft head is active). Nothing is stored.
3. `POST .../cycles` issues: one cycle plus one bill per flat, in one transaction. It is refused (400)
   while any problem remains or no charge head is active, and (409) if the period overlaps an issued
   cycle. The society row is locked while issuing, so two issues can't race.
4. Bill numbers are `yyyyMM-nnnn` (period start month, then the flat's position, ordered by wing name and
   flat number).
5. Residents of each flat get a `MAINTENANCE_BILL_ISSUED` push with their amount and due date.

**Bill statuses:** `UNPAID` → `PARTIAL` → `PAID` follow the payments (voiding a payment moves them back).
`WAIVED` (from UNPAID or PARTIAL, with a reason; money already paid stays on the bill, the rest is written
off) and `CANCELLED` (via cycle cancellation) are final. Each bill reports `amountPaid` and `outstanding`.

**Cancelling a cycle** (with a reason) cancels its unpaid and waived bills and frees the period for a
new cycle. It is refused (409) while any of its bills received money: void those payments first.

**Late charges.** When a cycle is previewed or issued, every UNPAID / PARTIAL bill that is overdue on
the new period's start date (due date + `graceDays` has passed) gets a `LATE_FEE` line on the same flat's
new bill, named "Late charges", with a note saying which bill and how it was worked out:

- `FIXED`: `lateFeeValue` once per overdue bill (quantity 1).
- `SIMPLE_INTEREST`: outstanding × `lateFeeValue`% × days / 365, rounded like other lines. Days run from
  the due date (grace decides *whether* to charge, not from when) or, after the first charge, from where
  the last charge stopped (quantity = days).

Each late line remembers its source bill and the day it covers up to. Lines on a cancelled bill don't
count, so cancelling and re-issuing a cycle charges the same late fees again rather than skipping them.
Cancelling an *older* cycle does not remove late charges already added to later bills; waive those if
needed.

## Opening balances

`POST .../opening-balances` takes `{asOf, dueDate, entries: [{flatId, amount, note?}]}` and creates a
one-off cycle of kind `OPENING_BALANCE` (period = `asOf`) with one `OB-nnnn` bill per listed flat and a
single "Opening balance" line. It is ignored when suggesting or overlap-checking regular periods. Only
one can be issued per society (409 otherwise); cancel it to enter the balances again. Opening-balance
bills are paid, waived, reported and late-charged like any other bill.

## Payments

Payments are offline (`CASH`, `CHEQUE`, `UPI`, `BANK_TRANSFER`) and recorded by the society with a paid-on
date (not in the future), an optional reference (cheque number, UPI id) and note.

- Each gets the society's next receipt number, `R-000001`, `R-000002` … (void payments keep theirs).
- The amount is applied to the flat's open bills oldest due date first; `billId` names one bill to fill
  first, the rest still goes oldest first. Amounts above what the flat owes are refused (400): there is
  no advance credit yet.
- Voiding (with a reason) takes the payment back off its bills and keeps it, and its allocations, as
  history. It is refused when one of its bills was waived since, as the write-off would silently grow.
- The flat's residents get a `MAINTENANCE_PAYMENT_RECORDED` push.

Every write to bills or payments (issue, opening balances, pay, void, waive, cancel) takes a row lock on
the society first, so they never interleave and receipt numbers have no gaps or duplicates.

## Reports

- **Dues** — one row per flat that owes something: open bills, outstanding, overdue (past due date +
  `graceDays`), oldest due date. `overdueOnly=true` lists the defaulters. Residents get their own flat's
  row only ("my dues"); a flat that owes nothing has no row.
- **Reminders** — a push to the residents of every defaulting flat, or of the listed `flatIds`
  (`MAINTENANCE_DUES_REMINDER`). Returns how many flats and how much is overdue.
- **Collection per cycle** — `CycleResponse` has `totalPaid` and `totalOutstanding` next to `totalBilled`
  and the counts by status.
- **CSV** — dues, payments by paid-on date range (void ones included and marked), and the cycle's bills
  (now with `amount_paid` and `outstanding`).

## Permissions

| Code | Resident | Guard | Committee | Secretary (Admin inherits) |
|---|---|---|---|---|
| `MAINTENANCE.CHARGE.VIEW` | ✅ | — | ✅ | ✅ |
| `MAINTENANCE.CHARGE.UPDATE` | — | — | — | ✅ |
| `MAINTENANCE.BILL.VIEW` | own flat | — | ✅ | ✅ |
| `MAINTENANCE.BILL.CREATE` (cycles, opening balances) | — | — | — | ✅ |
| `MAINTENANCE.BILL.UPDATE` (waive, cancel cycle, send reminders) | — | — | — | ✅ |
| `MAINTENANCE.BILL.EXPORT` (cycle and dues CSV) | — | — | ✅ | ✅ |
| `MAINTENANCE.PAYMENT.VIEW` | own flat | — | ✅ | ✅ |
| `MAINTENANCE.PAYMENT.CREATE` | — | — | — | ✅ |
| `MAINTENANCE.PAYMENT.UPDATE` (void) | — | — | — | ✅ |
| `MAINTENANCE.PAYMENT.EXPORT` | — | — | ✅ | ✅ |

The view codes are granted to RESIDENT; COMMITTEE inherits them society-wide. Writes always need a
society-wide role. A treasurer can be a custom role with `MAINTENANCE.PAYMENT.CREATE` plus the view and
export codes.

**Tenants (V16):** "own flat" means the owner side. A flat's OWNER and FAMILY residents (and flat roles
with no resident type) see its bills, payments and dues and get its maintenance pushes. TENANT residents
see and get them only when the society turns on the `tenantsSeeMaintenance`
[society setting](society.md#society-settings) (off by default). Lists return nothing for a tenant;
detail endpoints return 403. `MaintenanceAudience` applies the rule in the controllers and the push
listener.

## Endpoints (M7a)

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/api/societies/{societyId}/maintenance/settings` | `CHARGE.VIEW` | Defaults if never saved |
| PUT | `/api/societies/{societyId}/maintenance/settings` | `CHARGE.UPDATE` | `{billingFrequency, dueDays, roundToRupee}` |
| GET | `/api/societies/{societyId}/maintenance/charge-heads` | `CHARGE.VIEW` | In bill order |
| POST | `/api/societies/{societyId}/maintenance/charge-heads` | `CHARGE.UPDATE` | `{name, calcType, amount, active?, sortOrder?}` |
| PATCH | `/api/maintenance/charge-heads/{id}` | `CHARGE.UPDATE` | Any subset of the fields |
| DELETE | `/api/maintenance/charge-heads/{id}` | `CHARGE.UPDATE` | 204 |
| GET | `/api/societies/{societyId}/maintenance/cycles/next` | `BILL.CREATE` | Suggested `{periodStart, periodEnd, dueDate}` |
| POST | `/api/societies/{societyId}/maintenance/cycles/preview` | `BILL.CREATE` | Same body; bills + problems |
| POST | `/api/societies/{societyId}/maintenance/cycles` | `BILL.CREATE` | Issue → 201 cycle |
| GET | `/api/societies/{societyId}/maintenance/cycles` | `BILL.VIEW` (society-wide) | With bill count, total, counts by status |
| GET | `/api/maintenance/cycles/{id}` | `BILL.VIEW` (society-wide) | |
| POST | `/api/maintenance/cycles/{id}/cancel` | `BILL.UPDATE` | `{reason}` |
| GET | `/api/maintenance/cycles/{id}/export` | `BILL.EXPORT` | CSV of the cycle's bills |
| GET | `/api/maintenance/bills?societyId=&cycleId=&flatId=&status=&page=&size=` | `BILL.VIEW` | Residents: own flats only |
| GET | `/api/maintenance/bills/{id}` | `BILL.VIEW` (own flat or society-wide) | With lines |
| POST | `/api/maintenance/bills/{id}/waive` | `BILL.UPDATE` | `{reason}`; UNPAID only |

`GET /api/maintenance/bills/{id}` and the waive response also list the bill's `payments` (receipt, paid-on,
method, the part that went to this bill; void payments left out).

## Endpoints (M7b)

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/api/societies/{societyId}/maintenance/opening-balances` | `BILL.CREATE` | `{asOf, dueDate, entries:[{flatId, amount, note?}]}` → 201 cycle |
| POST | `/api/societies/{societyId}/maintenance/payments` | `PAYMENT.CREATE` | `{flatId, amount, paidOn, method, reference?, note?, billId?}` → 201 with allocations |
| GET | `/api/maintenance/payments?societyId=&flatId=&from=&to=&includeVoid=&page=&size=` | `PAYMENT.VIEW` | Newest first; residents: own flats only |
| GET | `/api/maintenance/payments/{id}` | `PAYMENT.VIEW` (own flat or society-wide) | The receipt, with allocations |
| POST | `/api/maintenance/payments/{id}/void` | `PAYMENT.UPDATE` | `{reason}`; 409 if already void or a paid bill was waived |
| GET | `/api/societies/{societyId}/maintenance/payments/export?from=&to=` | `PAYMENT.EXPORT` | CSV by paid-on date, at most 366 days |
| GET | `/api/societies/{societyId}/maintenance/dues?overdueOnly=` | `BILL.VIEW` | Residents: own flat's row only |
| GET | `/api/societies/{societyId}/maintenance/dues/export?overdueOnly=` | `BILL.EXPORT` | CSV |
| POST | `/api/societies/{societyId}/maintenance/dues/remind` | `BILL.UPDATE` | Optional `{flatIds}` → `{flatCount, totalOverdue}` |

Flats: `POST /api/wings/{wingId}/flats` and `PATCH /api/flats/{id}` accept an optional `areaSqft`
(omitted on PATCH leaves it unchanged); `FlatResponse` includes it.

## Data model (`V13__maintenance.sql`)

| Table | Notes |
|---|---|
| `flat.area_sqft` | `NUMERIC(8,2)`, optional |
| `maintenance_settings` | One row per society, cascades with it |
| `maintenance_charge_head` | Cascades with the society; unique name per society |
| `maintenance_cycle` | Unique `(society_id, period_start)` among ISSUED cycles |
| `maintenance_bill` | Unique per `(cycle, flat)` and `(cycle, bill_number)`; status + reason/by/at |
| `maintenance_bill_line` | Copy of each charge at issue time: name, type, rate, quantity, amount |

`V14__maintenance_payments.sql` (M7b):

| Change | Notes |
|---|---|
| `maintenance_settings` + `late_fee_type`, `late_fee_value`, `grace_days` | Defaults NONE / 0 / 0 |
| `maintenance_cycle.kind` | `REGULAR` / `OPENING_BALANCE`; the period index now covers regular cycles only, plus one issued opening cycle per society |
| `maintenance_bill.amount_paid` | 0 ≤ amount_paid ≤ total |
| `maintenance_bill_line` + `note`, `source_bill_id`, `charged_through` | Line types gain `LATE_FEE`, `OPENING_BALANCE` |
| `maintenance_payment` | Flat, amount > 0, paid on, method, reference, note, `RECORDED`/`VOID`, recorded/voided by and at; unique receipt number per society |
| `maintenance_payment_allocation` | Payment → bill → amount; kept after a void |

Money is `NUMERIC(12,2)` / `BigDecimal`. Cycles, bills and payments don't cascade, so a society or flat
with billing history can't be deleted (409).

## Code

`maintenance/` — `controller/` (`MaintenanceConfigController`, `MaintenanceBillingController`,
`MaintenancePaymentController`, `MaintenanceReportController`), `service/` (`MaintenanceConfigService`,
`BillingService`, `PaymentService`, `MaintenanceReportService`, pure `BillCalculator`, `BillingPeriods`,
`LateFees` and `PaymentAllocator`, `MaintenanceNotificationListener`), `model/`, `repository/`, `dto/`.
Uses `common`, `authorization`, `society`, `user`, `notification` (see `ModuleDependencyTest`).

Tests: `BillCalculatorTest`, `BillingPeriodsTest`, `LateFeesTest`, `PaymentAllocatorTest` (pure);
`MaintenanceIntegrationTest` (access, issue, overlap, frozen lines, waive, cancel, export, push) and
`MaintenancePaymentIntegrationTest` (allocation, named bill, overpayment, void, waive after payment, late
fees across cancel/re-issue, settings, opening balances, dues/defaulters/reminders, payments CSV, pushes).
Both import the top-level `RecordingPushConfig`, so they share one Spring context.

## Follow-ups

- Advance payments: today a payment above the flat's outstanding is refused. Carrying credit to the next
  bill would need a credit balance per flat applied at issue time.
- Receipt numbers run per society for ever (`R-000001` …); a financial-year prefix could be a setting.

- Web: a flat's area can only be set from the billing preview ("Set area" on a flat it flags), not yet in
  the structure screen's flat editor; no outstanding-dues tile on the dashboard yet.

## Web app (M7c)

Angular app (`Society Management - Web`), `src/app/features/maintenance/`, one **Maintenance** nav entry
(`MAINTENANCE.BILL.VIEW`) with tabs:

| Route | Screen |
|---|---|
| `/maintenance` | Dues per flat: outstanding / overdue totals, overdue-only toggle, record payment and remind per flat, remind all defaulters, dues CSV. Residents see their own flat. |
| `/maintenance/bills`, `/bills/:id` | Bills with status / cycle / flat filters (kept in the URL); bill with its lines, the payments that paid it, record payment (that bill first), waive, and **Print bill** (a clean bill document: society, bill no., flat, period, due date, particulars, paid, amount due). |
| `/maintenance/payments`, `/payments/:id` | Payments with date range and show-void filters, CSV export; printable receipt with the bills it paid, void with reason. |
| `/maintenance/cycles`, `/cycles/new`, `/cycles/:id` | Cycles with collection progress; new cycle = suggested period → preview (problems block issuing; flats missing an area get a "Set area" button) → issue; cycle with billed / collected / outstanding, its bills, CSV, cancel. |
| `/maintenance/opening-balances` | One amount (and note) per flat, entered once. |
| `/maintenance/settings` | Charge heads (add, edit, turn off, delete) and billing rules incl. late fee and grace days. |

The screens hide actions by permission; "society-wide" (committee views, write actions) is approximated as
holding the permission through a role assigned without a flat, and the backend has the final say. The
command palette gets "Record maintenance payment" and "Issue maintenance bills".

Tests: `maintenance.api.spec.ts`, `maintenance-ui.spec.ts` (unit); `e2e/maintenance.spec.ts` (Playwright:
charge → preview/issue → part payment → resident sees dues, receipt and printed bill → void → cancel, cleaned up after; assumes society 1 has no other maintenance data).

Postman: folder **14. Maintenance** (40 requests: settings and late fees, charge heads, next period →
preview → issue, bills, payments and receipt, dues and reminders, exports, void / waive / cancel, opening
balances). The bills it creates are history, so the cleanup folder's flat, wing, society and secretary
deletes now accept 409.
