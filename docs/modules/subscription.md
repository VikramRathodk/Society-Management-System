# Subscription & billing (module 30)

How the platform charges societies for using it: plans and prices, each society's subscription and
trial, the platform's invoices to the society with GST, payments against them, reminders, and what
happens when a society doesn't pay. This is the **platform billing the society** — not maintenance
(the society billing its residents, module 14) and not online payment collection (module 16).
Planned 2026-11-15 on backend branch `feature/subscription`; build steps S1–S6 below.

## Today

Every society uses everything for free, forever. Society status is SETUP or LIVE; onboarding left a
SUSPENDED status for this module. There is no record of what a society was promised or owes.

## Proposed decisions (to confirm, see "Open questions")

1. **Plans are platform data**, managed by the super admin: name, price **per flat per month**
   (with a minimum monthly charge), yearly price (usually a discount), the **modules included**
   (e.g. *Basic*: visitors, notices, complaints, maintenance; *Standard*: + finance, policies,
   governance; *Premium*: everything), and limits (max flats). A plan change applies from the next
   billing period. Prices are frozen on each invoice.
2. **Every society has one subscription:** plan, billing cycle (monthly or yearly), status, current
   period, next invoice date, and a billing contact (the secretary by default). New societies start on
   a **free trial** (default 30 days, set per plan) when they go live; societies already on the
   platform get a subscription created by the super admin (or a "grandfathered" free plan).
3. **Status:** TRIAL → ACTIVE (first invoice paid, or a free plan) → PAST_DUE (an invoice is overdue)
   → SUSPENDED (still unpaid after the grace period) → back to ACTIVE when paid; CANCELLED when the
   society leaves (its data is kept, read-only).
4. **Invoices from the platform to the society:** issued at the start of each period for the flats
   counted that day, numbered per financial year (`SAH/2026-27/0042`), with **GST** (18%, CGST+SGST or
   IGST from the platform's and the society's states), due in N days. Proration on plan or flat-count
   changes mid-period is *not* done in this round: the next invoice uses the new numbers. Credit notes
   correct a wrong invoice; a discount can be given per society (percent, for a number of periods).
5. **Payments are recorded by the super admin** (bank transfer, UPI, cheque) against invoices, like
   maintenance payments; a receipt is issued. Paying online through a gateway (Razorpay or similar)
   comes with module 16.
6. **Reminders and suspension, run by a daily job:** reminder pushes and emails to the billing contact
   before the due date and after it; PAST_DUE on the day after the due date; SUSPENDED after the grace
   period (default 15 days). **Suspension never stops safety:** guards keep logging visitors and
   residents keep signing in, approving visitors and raising complaints. What stops is the committee's
   paid work (new bills, vouchers, policies, meetings), which becomes read-only, with a banner "Your
   subscription is suspended — pay invoice …". Paying lifts it at once.
7. **Plan modules are enforced** in the backend (a module the plan doesn't include answers 402 with a
   clear message) and hidden in the web, so selling plans means something. Core modules (sign-in,
   structure, residents, visitors, notices, complaints) are in every plan.

## Who may do what

| Action | Who | Permission |
|---|---|---|
| Manage plans; create/change any subscription; issue invoices, credit notes, discounts; record payments; suspend/reactivate by hand | SUPER_ADMIN (platform) | `PLATFORM.BILLING.UPDATE` |
| See every society's subscription, invoices, overdue list, revenue summary | SUPER_ADMIN (platform) | `PLATFORM.BILLING.VIEW` |
| See the society's own plan, invoices and receipts; download invoices; change the billing contact; ask for a plan change | SECRETARY and above | `SOCIETY.SUBSCRIPTION.VIEW` / `UPDATE` |

## Settings (platform, not per society)

`app.billing.platform-gstin`, legal name, address and state (on invoices); `app.billing.gst-percent`
(18); `app.billing.invoice-due-days` (15); `app.billing.grace-days` (15); `app.billing.reminder-days`
(e.g. `-3,1,7`); `app.billing.trial-days` default (30). Deployment properties first; a platform settings
page later if they need to change often.

## Data (V30, planned)

- `plan`: code, name, description, price per flat (monthly, yearly), minimum monthly charge, max
  flats, trial days, modules (array or child table), active, sort order.
- `subscription`: society (unique), plan, cycle, status, trial ends, current period start/end, next
  invoice date, billing contact user, discount percent and periods left, created/updated.
- `subscription_event`: subscription, what changed (plan, status, cycle, discount), from/to, by, at,
  reason — the subscription's history.
- `platform_invoice`: number, society, subscription, period, plan and prices frozen, flat count,
  amount, discount, taxable value, CGST/SGST/IGST, total, status (ISSUED | PAID | PARTLY_PAID |
  CANCELLED), issued at, due date, society's GSTIN and billing address at issue.
- `platform_payment`: invoice, amount, mode, reference, paid on, recorded by; receipts numbered.
- `platform_credit_note`: invoice, amount, reason, number.
- `society`: status gains SUSPENDED (kept separate from SETUP/LIVE as `billing_status`, or a new value —
  decided in S3).
- Permissions as in the table above.

## Build steps

| Step | What | Done when |
|---|---|---|
| S1 | V30, plans (CRUD), subscriptions, trial started at go-live, plan/cycle changes from the next period, subscription history, the society's view | Tests: trial on go-live; one subscription per society; changes take effect next period; history kept |
| S2 | Invoices: period run, flat count, GST split by state, numbering per year, discount, credit notes; payments and receipts; printable invoice | Tests: GST intra/inter-state; minimum charge; numbering; partial and full payment; credit note |
| S3 | Daily job (the backend's first scheduler): issue due invoices, reminders, PAST_DUE, SUSPENDED after grace, reactivate on payment; suspension rules on the paid modules; banner data | Tests: status transitions by date (fixed clock); suspended society can't create bills/vouchers but guards still log visitors; payment lifts it |
| S4 | Plan modules enforced in the backend (402) and exposed to the web | Tests: a Basic society gets 402 on finance; core modules always work |
| S5 | Web: platform Billing (plans, subscriptions, invoices, payments, overdue, revenue), society "Subscription" page (plan, invoices, receipts, contact), suspension banner, hidden modules | Unit tests, build |
| S6 | Postman folder, docs | Full Postman run |

## Open questions

1. **Pricing model:** per flat per month with a minimum (proposed), flat tiers by society size (e.g.
   up to 50 / 200 / 500 flats), or a flat fee per plan?
2. **Plans and modules:** gate modules by plan (proposed: Basic / Standard / Premium as above), or one
   plan with everything and only the price varying?
3. **Collecting money:** record payments by hand now and add a gateway with module 16 (proposed), or
   integrate Razorpay (or another gateway) in this module?
4. **GST invoices:** does the platform have a GSTIN (and which state) to print, or invoices without GST
   for now?
5. **What suspension stops:** the committee's paid modules only, keeping gate, residents and complaints
   working (proposed), or the whole society read-only, or sign-in blocked?
6. **Trial and existing societies:** 30-day trial from go-live (proposed)? Do the societies already on
   the platform (including the client's) move to a paid plan, a free "founding" plan, or a trial?
7. **Emails:** reminders by push only (no mail provider is set up today), or set up email (SMTP/SES)
   for invoices and reminders as part of this module?
