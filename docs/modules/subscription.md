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

## Progress

- **Branch:** `feature/subscription` now sits on top of `feature/governance` (so its migrations follow
  V29–V31); merge governance first, or both together.
- **S1 done (2026-11-15).** V32 adds `plan` (seeded BASIC ₹10/flat/month, ₹100/year, min ₹500, MAINTENANCE;
  STANDARD ₹20 / ₹200 / ₹1,000, + FINANCE, POLICIES, GOVERNANCE; PREMIUM ₹30 / ₹300 / ₹1,500, ALL — every
  plan a 30-day trial; the prices are placeholders to set before launch), `subscription` (one per society:
  plan, cycle, status, trial end, current period, next invoice date, pending plan/cycle, billing contact,
  discount), `subscription_event` (history) and permissions `PLATFORM.BILLING.VIEW/UPDATE` (super admin)
  and `SOCIETY.SUBSCRIPTION.VIEW/UPDATE` (SECRETARY and above).
  - The society module publishes `SocietyWentLive` (on go-live, and when a society is created live); with
    `app.billing.enabled=true` (default **false** — nothing starts until billing is switched on) the
    society starts a trial on `app.billing.default-plan` (STANDARD): status TRIAL, period today …
    today + trial days − 1, first invoice the day after.
  - `GET /api/plans` (active plans; all of them for the platform); `POST /api/platform/plans`,
    `PUT /api/platform/plans/{id}` (code unique, prices 0–99,999.99, trial 0–365 days, modules).
  - `GET /api/platform/subscriptions`; `POST /api/platform/subscriptions/start-all` (trial for every live
    society without one — how existing societies get theirs); `POST /api/societies/{id}/subscription
    {planId?, cycle}` (one society).
  - `PATCH /api/societies/{id}/subscription/plan {planId?, cycle?}` — during a trial at once; otherwise
    pending until the next period (the next invoice applies it). A plan whose `maxFlats` is below the
    society's flats is refused.
  - `PUT …/subscription/discount {percent, periods, note}`, `POST …/subscription/cancel {note}`.
  - The society's own: `GET /api/societies/{id}/subscription` (plan, dates, flats, charge per period at
    today's numbers, history), `PUT …/billing-contact {userId}` (an active society-wide member),
    `POST …/request-change {planId?, cycle?, note}` (recorded for the platform; nothing changes by itself).
  - A period's charge: monthly max(flats × per-flat monthly, minimum); yearly max(flats × per-flat yearly,
    12 × minimum).
  - Tests: `SubscriptionIntegrationTest` (8, with billing switched on).
- **S2 done (2026-11-15).** V33 adds `platform_invoice` (everything printed frozen at issue: plan, rate,
  minimum, flats, discount, taxable value, CGST/SGST/IGST, total, society name/address/state, platform
  GSTIN), `platform_payment` (receipts; `gateway_payment_id` unique for S3), `platform_credit_note`,
  `platform_counter` (numbers per kind and April–March year).
  - `POST /api/societies/{id}/subscription/invoices/issue-due` (platform) issues the next period's invoice
    once it is due (204 if not yet): period = next invoice date … + 1 month/year − 1 day; a pending plan
    or cycle applies now; a discount applies and counts down; a trial becomes ACTIVE; due in
    `app.billing.invoice-due-days` (15). One invoice per subscription period (unique index).
  - GST: with `app.billing.platform-gstin` set, `app.billing.gst-percent` (18) split CGST + SGST when the
    society's state equals `app.billing.state`, IGST otherwise; without a GSTIN, no tax.
  - Numbers: `SAH/2026-27/0042` (invoices), `SAH-R/…` (receipts), `SAH-CN/…` (credit notes); prefix
    `app.billing.number-prefix`.
  - `POST /api/platform-invoices/{id}/payments {amount, mode: BANK_TRANSFER|UPI|CHEQUE|CASH, reference?,
    paidOn?}` (at most the balance, not in the future) and `…/credit-notes {amount, reason}`; status
    ISSUED → PARTLY_PAID → PAID. A PAST_DUE or SUSPENDED society with nothing overdue left becomes ACTIVE.
  - `GET /api/societies/{id}/subscription/invoices` (the society), `GET /api/platform/invoices?status=&overdue=`
    (platform), `GET /api/platform-invoices/{id}` (either; everything for a printable invoice, with the
    seller's legal name, GSTIN, address and state).
  - Pushes `PLATFORM_INVOICE` and `PLATFORM_PAYMENT` to the billing contact, or else SECRETARY and above.
  - Tests: `InvoiceIntegrationTest` (7).
- **S5 and S6 done (2026-11-17)**, built before S3/S4 because they need no outside accounts.
  - V34 adds `platform_invoice.last_reminded_on`. `BillingRunService.run(day)` goes through every
    subscription that isn't cancelled, each in its own transaction (one failing never stops the others):
    issues every invoice that is due (catching up missed periods), reminds on the days in
    `app.billing.reminder-days` (`-3,1,7` relative to the due date, at most once a day per invoice), moves
    ACTIVE → PAST_DUE the day after an invoice's due date and → SUSPENDED once it is still unpaid after
    `app.billing.grace-days` (15). Paying (S2) makes the society ACTIVE again at once. `BillingRunJob` runs it
    at `app.billing.run-cron` (06:00 in the report zone) when billing is on; `POST /api/platform/billing/run`
    (platform) runs it on demand and answers the counts.
  - Pushes: `PLATFORM_INVOICE_REMINDER` (due soon / due today / overdue) and `SUBSCRIPTION_STATUS`
    (PAST_DUE, SUSPENDED, ACTIVE again) to the billing contact, or else SECRETARY and above.
  - **Gate (S5 suspension + S6 plan modules)** — one place, no change in the other modules: the
    authorization module has a `SocietyGate` hook that every per-society permission check of the signed-in
    caller (`can`, `canSocietyWide`, `canOnFlat`, `requireIn`) passes through, and `SubscriptionGate`
    implements it. Permissions map to modules by prefix (MAINTENANCE, FINANCE, POLICY → POLICIES,
    GOVERNANCE).
    - A module outside the plan answers **402** ("Finance and accounts isn't part of the Basic plan …") for
      reads and writes.
    - SUSPENDED or CANCELLED: the paid modules' writes (any permission not ending in VIEW/EXPORT) are refused
      with **402** "Your subscription is suspended. Pay invoice SAH/… (₹… due since …) to continue"; flags
      such as `canDraft` simply turn false. Residents' own actions stay open
      (`app.billing.suspended-allowed`: `POLICY.ACKNOWLEDGEMENT.CREATE,GOVERNANCE.VOTE.CREATE`). Reads,
      exports, core modules (visitors, complaints, notices, structure, residents) and the subscription
      itself always work.
    - Platform grants (super admin) never pass through the gate; nor do audiences computed for
      notifications; societies without a subscription (billing off) are never gated.
  - `GET /api/societies/{id}/subscription/access` (any member of the society): `billing`, `status`,
    `planName`, `modules` (paid modules in the plan), `readOnly`, `trialEndsOn`, and the overdue invoice
    (`overdueInvoiceId/Number/Balance/Since`) — what the web needs to hide modules and show the banner.
  - Tests: `BillingRunIntegrationTest` (7): transitions by date, reminders once each, catch-up, paid up is
    never gated, suspended society refused paid work but guards log visitors and paying lifts it, Basic gets
    402 on finance/policies/meetings, outsiders can't read the access state.
- **S3 done (2026-11-17), Razorpay.** V35 adds `razorpay_order` (invoice, Razorpay order id, amount in paise,
  CREATED | PAID | FAILED, payment id, error) and `razorpay_webhook_event` (once per event id, with what was
  done).
  - Settings `app.razorpay.key-id/key-secret/webhook-secret` (`RAZORPAY_*`) and `app.razorpay.mode`
    (`test`; `live` refuses to start without all three and a `rzp_live_` key). Without keys, "Pay now"
    answers 409 "Online payment isn't set up yet".
  - `POST /api/platform-invoices/{id}/razorpay-order` (SECRETARY and above, or the platform): an order for
    the amount due (an open order for the same amount is reused) → `{keyId, orderId, amount (paise),
    currency, invoiceId, invoiceNumber, merchantName}` for Checkout.
  - `POST /api/platform-invoices/{id}/razorpay-verify {orderId, paymentId, signature}`: HMAC-SHA256 of
    `orderId|paymentId` with the key secret; records the payment (mode RAZORPAY, receipt) → the invoice.
    400 on a bad signature; 409 when it can't be recorded (below).
  - `POST /api/webhooks/razorpay` (public; `X-Razorpay-Signature` = HMAC of the raw body with the webhook
    secret, else 400; `X-Razorpay-Event-Id` makes it idempotent): `payment.captured` records the payment if
    the checkout return didn't; `payment.failed` marks the order FAILED; others ignored. Always 200 with
    `{outcome}` once the signature is valid, so Razorpay stops retrying.
  - Recorded once per Razorpay payment id (the order row is locked, `gateway_payment_id` unique). Not
    recorded, and noted on the order for a refund in the Razorpay dashboard: an amount that differs from
    the order's, or more than the invoice still asks for (paid another way meanwhile).
  - Tests: `RazorpayIntegrationTest` (8, gateway faked, signatures computed with test secrets).
- **S4 done (2026-11-17), email.** `notification.Mailer` (best-effort, never throws) with `LoggingMailer`
  (`MAIL_PROVIDER=log`, default) and `SmtpMailer` (`MAIL_PROVIDER=smtp`: `SMTP_HOST/PORT/USERNAME/PASSWORD`,
  `MAIL_FROM`; any SMTP endpoint). V36 `email_log` (to, user, society, subject, kind INVOICE | RECEIPT |
  REMINDER | STATUS, related id, SENT | FAILED | SKIPPED, error), written in its own transaction.
  - `BillingEmails` (after commit, next to the pushes) mails the billing contact, or else SECRETARY and
    above: new invoice (amount, due date, "View and pay" link `<web>/subscription?invoice=<id>`), receipt,
    reminders (due soon / today / overdue), suspension and reactivation. Plain text + simple HTML.
  - Someone without an email is logged SKIPPED (push only); an SMTP failure is logged FAILED and never
    blocks billing.
  - Tests: `BillingEmailIntegrationTest` (4, recording mailer).
  - Real mail needs an SMTP sender account (e.g. Gmail with an app password); the product owner's test
    recipient is noted outside the repo.
- **S7 (web), 2026-11-18**, on web branch `feature/subscription`: society **Subscription** page (plan, period,
  charge, invoices with "Pay" through Razorpay Checkout, billing contact, ask for another plan, history;
  `/subscription?invoice=<id>` from email links), printable **invoice** page `/subscription/invoices/:id`
  (GST lines, receipts, credit notes; the platform records payments and credit notes there), platform
  **Billing** page `/billing` (totals; societies with change plan / discount / issue due invoice / cancel;
  invoices with an overdue filter; plans), "Run billing now" and "Start trials". Paid modules outside the
  plan are hidden from the nav and their routes (`SubscriptionAccessStore`, `planModuleGuard`; never for the
  platform); a red banner on every page while suspended or cancelled; any 402 refreshes it. Billing pushes
  open the invoice or the subscription page.
- **S8 done (2026-11-18), Postman** folder **22. Subscription & billing** (57 requests; Cleanup is now 23).
  Run with billing off (the default): plans (create, duplicate 409, edit, 400, 403); the main society on a
  Basic trial (finance, meetings and policies 402; maintenance and the platform unaffected), billing contact,
  change request, Premium at once during the trial; a second society on a no-trial plan: invoice with the
  minimum charge and GST as configured, 204 when nothing is due, part payment, overpayment 400, credit note,
  paid in full, 409 when settled; Razorpay order (409 without keys), webhook with a bad signature 400, a
  signed webhook (needs `RAZORPAY_WEBHOOK_SECRET` = the `razorpayWebhookSecret` variable,
  `postman_webhook_secret`) for an unknown order and its duplicate; platform lists and the billing run;
  cancelling makes the society read-only (402 on writes). Full run 2026-11-18: 728/729 assertions, the one
  failure being the old Cleanup "Delete the test resident" check.
- **Module done** apart from trying Razorpay with real test keys and SMTP with a real account.

## Pricing v2: a price per society, a featured plan and a recommendation (planned 2026-11-18)

The product owner found per-flat pricing hard to sell. Market check (MyGate, ADDA, NoBrokerHood, Gatekeeper
Zero, Society Saathi, Msoft, MaintainEase): the market charges ₹9–25 per flat per month for accounting apps
and ₹35–50 for security suites, and several offer a free tier. Decisions (product owner, 2026-11-18):

1. **A fixed price per society per month** that covers a number of flats, plus a charge per extra flat beyond
   them (so large societies still pay a fair rate); yearly = 10 × monthly (two months free). Prices are
   before GST, and every page says "+ 18% GST".
2. **Plans** (seeded; all editable by the platform):

   | Plan | Monthly | Yearly | Flats included | Each extra flat / month | Hard limit | Modules | Trial |
   |---|---|---|---|---|---|---|---|
   | FREE | ₹0 | ₹0 | 15 | none | 15 flats | core only | none |
   | BASIC | ₹499 | ₹4,990 | 30 | ₹10 (₹100/yr) | none | + maintenance | 30 days |
   | STANDARD ★ | ₹699 | ₹6,990 | 50 | ₹12 (₹120/yr) | none | + finance, policies | 30 days |
   | PREMIUM | ₹999 | ₹9,990 | 75 | ₹14 (₹140/yr) | none | everything (governance, future modules) | 30 days |

3. **One featured plan** ("Most popular"), a flag the platform sets in the plan editor; at most one at a time.
   STANDARD to start.
4. **A recommendation for a flat count**: each plan says from how many flats it is the recommended one
   (`recommended_from_flats`: FREE 1, BASIC 16, STANDARD 31, PREMIUM 151); the recommendation is the active
   plan with the highest such number not above the flat count, among plans whose hard limit fits. Shown on
   the landing page ("How many flats?") with each plan's total, and inside the app on the Subscription page.
5. **The hard limit is enforced when flats are added** (single flat and the setup wizard's bulk structure),
   not only on a plan change: "The Free plan is for up to 15 flats. Upgrade to add more."
6. **A ₹0 period issues no invoice**: the subscription moves on to the next period (a trial still ends).

### Build steps

| Step | What | Done when |
|---|---|---|
| P1 | Backend: V37 (plan price per society, included flats, extra-flat rates, featured, recommended-from; invoice keeps base price, included and extra flats; FREE plan; new prices), charge = price + extra flats × rate, ₹0 periods skip the invoice, flat limit on adding flats (a port in `society` implemented by `subscription`), `GET /api/public/plans/quote?flats=` and the society's recommendation | Tests: charge with and without extra flats, yearly; one featured plan; recommendation by flat count; Free blocks the 16th flat (single and bulk); free period issues no invoice; existing tests updated |
| P2 | Web: plan editor fields; landing pricing (featured badge, flats box, totals, recommended highlight, "+ GST"); Subscription page recommendation and price breakdown; invoice shows base + extra flats | Unit tests, build |
| P3 | Postman folder 22 updated, docs | Full Postman run |

### Progress

- **P1 done (2026-11-18).** V37: `plan.price_monthly/price_yearly` (renamed from the per-flat prices),
  `included_flats`, `extra_flat_monthly/yearly`, `featured` (unique partial index: at most one),
  `recommended_from_flats`; `minimum_monthly` dropped; the four plans re-seeded as in the table above (FREE
  added). `platform_invoice` freezes `base_price`, `included_flats`, `extra_flats` and `extra_flat_rate`
  (renamed from `minimum_charge` / `rate_per_flat`; old dev invoices keep their old numbers there).
  - Charge = price + extra flats × extra-flat rate. Plan validation: extra flats need a price unless
    `maxFlats` stops the plan at its included flats. Featuring a plan takes the badge off the other one.
  - A ₹0 period (Free) issues no invoice: `issueDue` moves the subscription into the period (ending a trial)
    and carries on to the next due one, so the billing run's catch-up still works.
  - `GET /api/public/plans/quote?flats=N` (public, 1–100,000) and `GET /api/societies/{id}/subscription/recommendation`
    (SECRETARY and above, or the platform): `{flats, recommendedPlanId, quotes: [{plan, flats, extraFlats, fits,
    monthly, yearly, recommended}]}`. Recommended = the active plan that fits with the highest
    `recommendedFromFlats` ≤ N; ties go to the featured plan, then sort order.
  - Flat limit: the `society` module's `FlatLimit` port (checked by `FlatService.addFlat` and the setup
    wizard's bulk `StructureService.generate`) is implemented by `subscription.PlanFlatLimit`: past the plan's
    `maxFlats` answers **402** "The Free plan is for up to 15 flats; this would make 16. Upgrade …".
  - `SubscriptionResponse.extraFlats`; `InvoiceResponse` has `basePrice/includedFlats/extraFlats/extraFlatRate`
    instead of `ratePerFlat/minimumCharge`.
  - Tests: `SubscriptionIntegrationTest` 15 (charge, quote and recommendation, the society's recommendation,
    one featured, extra-flat validation, Free limit single and bulk, free periods without invoices); invoice
    amounts updated. Full suite 499 green.
