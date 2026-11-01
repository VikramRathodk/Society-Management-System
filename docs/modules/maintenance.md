# Maintenance Management (`maintenance`)

Society maintenance dues: what each flat is charged, billing it per period, recording payments, and
who hasn't paid. Module 14 in [MODULES.md](../MODULES.md).

**Status:** 🟡 M7a (configuration + billing) built 2026-11-01 · M7b (payments, reports) and M7c (web
screens) planned.

## Scope

| In | Later / elsewhere |
|---|---|
| Per-society settings and charge heads | Online payment gateway — Payment module (16) |
| Billing cycles: preview, issue, cancel | Income/expense ledger — Finance module (15) |
| Bills per flat with frozen line items; waive | GST on maintenance |
| Recording offline payments, receipts (M7b) | Charges by flat type (1BHK/2BHK) |
| Defaulters and collection reports, CSV (M7a: cycle CSV) | Non-occupancy charges for tenant flats |
| Late fee / interest rules (M7b) | |
| Opening balances for existing societies (M7b) | |

## Configuration (dynamic, per society)

Changing configuration only affects cycles issued afterwards; an issued bill keeps its own copy of the
charges.

**Settings** (`maintenance_settings`, defaults when never saved):

| Setting | Default | Meaning |
|---|---|---|
| `billingFrequency` | `MONTHLY` | `MONTHLY`, `QUARTERLY`, `HALF_YEARLY`, `YEARLY`: length of the suggested next period |
| `dueDays` | 15 | Suggested due date = period start + this many days (0–365) |
| `roundToRupee` | true | Round each bill line to whole rupees (half up); false keeps paise |

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

**Bill statuses:** `UNPAID` → (`PARTIAL` → `PAID`, M7b). `WAIVED` (from UNPAID, with a reason) and
`CANCELLED` (via cycle cancellation) are final.

**Cancelling a cycle** (with a reason) cancels its unpaid and waived bills and frees the period for a
new cycle. Once payments exist (M7b) a cycle with paid bills will be refused instead.

## Permissions

| Code | Resident | Guard | Committee | Secretary (Admin inherits) |
|---|---|---|---|---|
| `MAINTENANCE.CHARGE.VIEW` | ✅ | — | ✅ | ✅ |
| `MAINTENANCE.CHARGE.UPDATE` | — | — | — | ✅ |
| `MAINTENANCE.BILL.VIEW` | own flat | — | ✅ | ✅ |
| `MAINTENANCE.BILL.CREATE` | — | — | — | ✅ |
| `MAINTENANCE.BILL.UPDATE` (waive, cancel cycle) | — | — | — | ✅ |
| `MAINTENANCE.BILL.EXPORT` | — | — | ✅ | ✅ |
| `MAINTENANCE.PAYMENT.VIEW` (M7b) | own flat | — | ✅ | ✅ |
| `MAINTENANCE.PAYMENT.CREATE` (M7b) | — | — | — | ✅ |
| `MAINTENANCE.PAYMENT.UPDATE` (M7b, void) | — | — | — | ✅ |
| `MAINTENANCE.PAYMENT.EXPORT` (M7b) | — | — | ✅ | ✅ |

The view codes are granted to RESIDENT; COMMITTEE inherits them society-wide. Writes always need a
society-wide role. A treasurer can be a custom role with `MAINTENANCE.PAYMENT.CREATE` plus the view and
export codes.

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

Money is `NUMERIC(12,2)` / `BigDecimal`. Cycles and bills don't cascade, so a society or flat with billing
history can't be deleted (409).

## Code

`maintenance/` — `controller/` (`MaintenanceConfigController`, `MaintenanceBillingController`),
`service/` (`MaintenanceConfigService`, `BillingService`, pure `BillCalculator` and `BillingPeriods`,
`MaintenanceNotificationListener`), `model/`, `repository/`, `dto/`. Uses `common`, `authorization`,
`society`, `user`, `notification` (see `ModuleDependencyTest`).

Tests: `BillCalculatorTest`, `BillingPeriodsTest` (pure), `MaintenanceIntegrationTest` (access, issue,
overlap, frozen lines, waive, cancel, export, push).

## M7b plan — payments and reports

- `maintenance_payment` (flat, amount, paid on, method CASH/CHEQUE/UPI/BANK_TRANSFER, reference,
  receipt number per society, recorded by, void fields) and `maintenance_payment_allocation`
  (payment → bill → amount).
- Recording a payment applies it to the flat's oldest unpaid bills first; the form can name one bill
  instead. Bill status becomes PARTIAL / PAID from its allocations. Voiding a payment (with a reason)
  removes its allocations.
- Cancelling a cycle with paid bills is refused; waiving a PARTIAL bill writes off the remainder.
- Settings gain late payment: `lateFeeType` NONE / FIXED / SIMPLE_INTEREST, `lateFeeValue`,
  `graceDays`; applied as a "Late charges" line on the next cycle's bill.
- Opening balances: a one-off bill per flat for dues from before the app.
- Reports: dues per flat (outstanding), defaulters list with a reminder push, collection summary per
  cycle, payments CSV. Push `MAINTENANCE_PAYMENT_RECORDED` to the flat.

## M7c plan — web app

Maintenance settings and charge heads; area in the flat editor; billing cycles (list, preview → issue,
detail with collection progress); bill detail with waive; record-payment dialog and printable receipt;
defaulters report; "My dues" for residents; outstanding tile on the dashboard. Plus a Postman folder.
