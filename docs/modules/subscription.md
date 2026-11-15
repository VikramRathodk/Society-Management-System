# Subscription & billing (module 30)

How the platform charges societies for using it: plans and prices, each society's subscription and
trial, the platform's invoices to the society with GST, payments against them, reminders, and what
happens when a society doesn't pay. This is the **platform billing the society** — not maintenance
(the society billing its residents, module 14) and not online payment collection (module 16).
Planned 2026-11-15 on backend branch `feature/subscription`; build steps S1–S6 below.

## Today

Every society uses everything for free, forever. Society status is SETUP or LIVE; onboarding left a
SUSPENDED status for this module. There is no record of what a society was promised or owes.

## Decisions (product owner, 2026-11-15)

1. **Plans are platform data**, managed by the super admin: name, price **per flat per month**
   (with a minimum monthly charge), yearly price (usually a discount), the **modules included**
   (e.g. *Basic*: visitors, notices, complaints, maintenance; *Standard*: + finance, policies,
   governance; *Premium*: everything), and limits (max flats). A plan change applies from the next
   billing period. Prices are frozen on each invoice.
2. **Every society has one subscription:** plan, billing cycle (monthly or yearly), status, current
   period, next invoice date, and a billing contact (the secretary by default). New societies start on
   a **free trial** (default 30 days, set per plan) when they go live; societies already on the
   platform (including the client's) get the same 30-day trial from the day billing is switched on.
3. **Status:** TRIAL → ACTIVE (first invoice paid, or a free plan) → PAST_DUE (an invoice is overdue)
   → SUSPENDED (still unpaid after the grace period) → back to ACTIVE when paid; CANCELLED when the
   society leaves (its data is kept, read-only).
4. **Invoices from the platform to the society:** issued at the start of each period for the flats
   counted that day, numbered per financial year (`SAH/2026-27/0042`), with **GST** (18%, CGST+SGST or
   IGST from the platform's and the society's states), due in N days. Proration on plan or flat-count
   changes mid-period is *not* done in this round: the next invoice uses the new numbers. Credit notes
   correct a wrong invoice; a discount can be given per society (percent, for a number of periods).
5. **Societies pay online through Razorpay** in this module: the billing contact clicks "Pay now" on
   an invoice, the backend creates a Razorpay order for the amount due, the web opens Razorpay
   Checkout (UPI, cards, netbanking), and the payment is recorded only when the backend has verified
   it — the checkout signature on return, and the `payment.captured` **webhook** (signature checked
   with the webhook secret) as the source of truth, so a closed browser tab still gets recorded.
   Webhooks are idempotent (keyed by Razorpay payment id). Keys and secrets are deployment settings
   (test keys in dev; the backend refuses to start a live mode without them). The super admin can
   still **record a payment by hand** (bank transfer, cheque) and refunds are done in the Razorpay
   dashboard and recorded as credit notes. A receipt is issued either way.
6. **Reminders and suspension, run by a daily job:** reminder pushes and emails to the billing contact
   before the due date and after it; PAST_DUE on the day after the due date; SUSPENDED after the grace
   period (default 15 days). **Suspension never stops safety:** guards keep logging visitors and
   residents keep signing in, approving visitors and raising complaints. What stops is the committee's
   paid work (new bills, vouchers, policies, meetings), which becomes read-only, with a banner "Your
   subscription is suspended — pay invoice …". Paying lifts it at once.
7. **Plan modules are enforced** in the backend (a module the plan doesn't include answers 402 with a
   clear message) and hidden in the web, so selling plans means something. Core modules (sign-in,
   structure, residents, visitors, notices, complaints) are in every plan.
8. **Email is set up in this module** (the platform's first): invoices (as a PDF attachment or a link),
   receipts and payment reminders go to the billing contact's email, besides pushes and the in-app
   banner. A mail port in `notification` with an SMTP implementation (works with SES, SendGrid, Zoho
   and others through their SMTP endpoints) and a logging implementation for dev, chosen by a property
   like push is (`MAIL_PROVIDER=smtp|log`). A billing contact without an email gets pushes only, and the
   society page asks for one.
9. **GST:** the platform's GSTIN, legal name, address and state are deployment settings printed on
   every invoice; 18% split into CGST+SGST (same state as the society) or IGST (other state). Without a
   GSTIN configured, invoices are issued without tax and say so.

## Who may do what

| Action | Who | Permission |
|---|---|---|
| Manage plans; create/change any subscription; issue invoices, credit notes, discounts; record payments; suspend/reactivate by hand | SUPER_ADMIN (platform) | `PLATFORM.BILLING.UPDATE` |
| See every society's subscription, invoices, overdue list, revenue summary | SUPER_ADMIN (platform) | `PLATFORM.BILLING.VIEW` |
| See the society's own plan, invoices and receipts; download invoices; change the billing contact; ask for a plan change | SECRETARY and above | `SOCIETY.SUBSCRIPTION.VIEW` / `UPDATE` |

## Settings (platform, not per society)

`app.billing.platform-gstin`, legal name, address and state (on invoices); `app.billing.gst-percent`
(18); `app.billing.invoice-due-days` (15); `app.billing.grace-days` (15); `app.billing.reminder-days`
(e.g. `-3,1,7`); `app.billing.trial-days` default (30). Razorpay: `RAZORPAY_KEY_ID`,
`RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`. Mail: `MAIL_PROVIDER`, `SMTP_HOST/PORT/USERNAME/
PASSWORD`, `MAIL_FROM`. Deployment properties first; a platform settings page later if they need to
change often.

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
- `platform_payment`: invoice, amount, mode (RAZORPAY | BANK_TRANSFER | UPI | CHEQUE), reference,
  paid on, recorded by (null for gateway payments); receipts numbered. Unique Razorpay payment id.
- `razorpay_order`: invoice, Razorpay order id, amount, status (CREATED | PAID | FAILED | EXPIRED),
  created by, created/updated at; `razorpay_webhook_event`: event id, type, payload, received at,
  processed at (idempotency and audit).
- `platform_credit_note`: invoice, amount, reason, number.
- `email_log`: to, subject, kind (INVOICE | RECEIPT | REMINDER), related id, status, error, sent at.
- `society`: status gains SUSPENDED (kept separate from SETUP/LIVE as `billing_status`, or a new value —
  decided in S3).
- Permissions as in the table above.

## Build steps

| Step | What | Done when |
|---|---|---|
| S1 | V30, plans (CRUD), subscriptions, trial started at go-live (and for existing societies when billing is switched on), plan/cycle changes from the next period, subscription history, the society's view | Tests: trial on go-live; one subscription per society; changes take effect next period; history kept |
| S2 | Invoices: period run, flat count, minimum charge, GST split by state (or none without a GSTIN), numbering per year, discount, credit notes; manual payments and receipts; printable invoice and receipt | Tests: GST intra/inter-state and without GSTIN; minimum charge; numbering; partial and full payment; credit note |
| S3 | Razorpay: order for an invoice's amount due, checkout return with signature check, `payment.captured` / `payment.failed` webhooks with signature check and idempotency, receipt on capture | Tests (gateway faked): signature valid/invalid; webhook twice records once; closed tab still recorded by webhook; amount mismatch refused |
| S4 | Email: mail port in `notification`, SMTP and logging implementations, templates for invoice, receipt and reminders, email log; billing contact's email | Tests: mails rendered and logged; missing email falls back to push; SMTP failure logged, never blocks billing |
| S5 | Daily job (the backend's first scheduler): issue due invoices, reminders (push + email), PAST_DUE, SUSPENDED after grace, reactivate on payment; suspension rules on paid committee work; banner data | Tests: status transitions by date (fixed clock); suspended society can't create bills/vouchers but guards still log visitors; payment lifts it |
| S6 | Plan modules enforced in the backend (402) and exposed to the web | Tests: a Basic society gets 402 on finance; core modules always work |
| S7 | Web: platform Billing (plans, subscriptions, invoices, payments, overdue, revenue), society "Subscription" page (plan, invoices with "Pay now" via Razorpay Checkout, receipts, contact), suspension banner, hidden modules | Unit tests, build |
| S8 | Postman folder (webhooks with test signatures), docs | Full Postman run |

## Answered questions (product owner, 2026-11-15)

1. **Pricing:** per flat per month with a minimum monthly charge; a yearly price at a discount.
2. **Plans:** Basic / Standard / Premium with different modules, enforced by the backend.
3. **Collecting money:** **Razorpay in this module** (orders, Checkout, verified webhooks), plus
   manual recording by the super admin.
4. **GST:** yes, with the platform's GSTIN and state as settings (no tax shown while unset).
5. **Suspension:** stops paid committee work only; guards, visitors, residents and complaints keep
   working.
6. **Trial:** 30 days for new societies from go-live and for existing ones from when billing starts.
7. **Email:** set up email (SMTP, provider-agnostic) for invoices, receipts and reminders.

Still needed before S3 and S4: a Razorpay account (test keys are enough to build) and an SMTP account
for the sending domain.
