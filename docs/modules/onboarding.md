# Society onboarding (module 7)

How a society gets onto the platform and from an empty record to a society its residents use. Planned
2026-11-13 on backend branch `feature/onboarding`; build steps O1–O5 below. Reuses the
[invitation](invitation.md) module for every person it brings in.

## Today

The super admin creates a society (name and address only), adds wings and flats one at a time on the
Structure page, then creates users or sends invitations one by one. There is no public way for a
society to sign up; the landing page only has "Request a demo" (an external link). A society is usable
the moment it exists.

## Decisions (product owner, 2026-11-13)

1. **Both ways in.** *Platform-led:* the super admin creates the society and invites its secretary.
   *Self-signup:* a secretary registers the society from the public site. Both end in the same setup
   wizard.
2. **Setup wizard.** Society profile is **mandatory** (with a free-text **notes** field for anything
   else worth recording). Bulk structure, residents from a sheet, and committee & key settings are
   **optional**: each can be skipped and finished later from the same page.
3. **Bulk residents become invitations.** Every row of the sheet becomes an invitation (the invitation
   module's rules, expiry, approval and accept flow); nobody gets an account they didn't confirm.
4. **Status: Setup → Live.** A new society starts in Setup; the secretary clicks **Go live** after the
   checklist. Suspended is left for subscriptions (module 30).

Assumed, to confirm (see "Open questions"): self-signup waits for the super admin's **approval** before
the society exists; while in Setup, residents can't sign in and nothing is pushed to them.

## How each path runs

### Platform-led

1. Super admin: **New society** (existing dialog) now also asks for the secretary's name and phone and
   creates a SECRETARY invitation straight away (link + QR, as today).
2. The society is created in **Setup**, source `PLATFORM`.
3. The secretary accepts the invitation and lands on the setup wizard.

### Self-signup

1. Public page `/register-society` (linked from the landing page as "Register your society"): society
   name, city, address, approximate number of flats; the applicant's name, phone, email, password; an
   optional message. A signed-in user applies with their existing account instead of a new password.
2. Saved as a **society application** (PENDING). The applicant sees "We'll call you to confirm"; no
   society or account exists yet. One pending application per phone; a simple per-IP rate limit.
3. Super admin: **Applications** list (pending first) → approve or reject with a reason. Approving
   creates the society (Setup, source `SELF_SIGNUP`), the applicant's account (from the stored
   password hash, unless they already had one) and their SECRETARY role, in one transaction, and pushes
   / shows a "you're approved, sign in" status on the application page.
4. Rejected applicants can see the reason on the same page (looked up by phone + application number).

Without an SMS provider there is no OTP; the super admin's call to confirm the applicant is the identity
check (as with demo requests today). OTP can be added later without changing the flow.

### Setup wizard (secretary and above, society in Setup — and reachable later while Live)

| Step | Required | What it does |
|---|---|---|
| 1. Society profile | **Yes** | Registration number, address line, city, state, PIN, contact phone and email, logo (optional), **notes** (free text, up to 2,000 characters). |
| 2. Wings & flats | No | Generate wings and flats from a pattern: wing name, floors from–to, flats per floor, numbering (e.g. `{floor}{nn}` → 101…1204), flats to leave out, optional ground-floor shops/units. Preview the list, then create. Existing flats are skipped, never duplicated. The one-by-one Structure page stays for corrections. |
| 3. Residents from a sheet | No | Upload CSV (Excel: save as CSV, as for bank statements): flat, name, phone, owner/tenant/family. Columns guessed from the names, preview with row errors (unknown flat, bad phone, duplicate phone, over `maxResidentsPerFlat`), fix and re-upload or drop bad rows. Rows are **staged**, not sent. |
| 4. Committee & key settings | No | Invite committee, treasurer and guards (the existing invite dialog, several at once); a short form with the society rules most societies change (invite expiry, residents can invite, tenants see maintenance, financial-year start), linking to Maintenance and Finance setup for the rest. |
| 5. Go live | — | Checklist with what's done and what's skipped; needs step 1 done. Going live sends the staged resident invitations and makes the society Live. |

A banner on every page while in Setup: "Your society is being set up — 2 of 4 steps done · Continue".

### Sending bulk invitations

Invitations are delivered as links today (the inviter shares them). For hundreds of residents the
wizard offers, at the moment they're sent (tokens are stored hashed, so links exist only then):

- **CSV of links** (flat, name, phone, link) for a WhatsApp broadcast or mail merge, and
- **printable QR cards**, one per flat, for the notice board or door-to-door.

Residents who later need a fresh link get one through the existing resend.

## Rules

- **Setup** — only society-wide roles (secretary, committee, treasurer, guard, …) can sign in; flat
  roles can't, resident invitations can't be accepted, and no push goes to residents (notices and bills
  can be prepared). Visitor and complaint screens work for testing.
- **Go live** is one-way in this round (no "back to Setup"); audited with who and when.
- **Existing societies** become Live with source `PLATFORM` in the migration; nothing changes for them.
- Profile fields stay editable on the society settings page after go-live.
- Nothing in onboarding bypasses the invitation module: rank rules, per-flat limits, expiry, resident
  approval all apply.

## Data (V23)

- `society`: `status` (SETUP | LIVE), `source` (PLATFORM | SELF_SIGNUP), `went_live_at/by`,
  `registration_number`, `address_line`, `city`, `state`, `pincode`, `contact_phone`, `contact_email`,
  `logo_photo_id` (existing photo storage), `notes`.
- `society_application`: society details, applicant name/phone/email, password hash (null when an
  existing user applied), `applicant_user_id`, message, status (PENDING | APPROVED | REJECTED),
  reviewed by/at, reason, `society_id` once approved, `created_ip`.
- `resident_import` + `resident_import_row`: the staged sheet per society (row, flat, name, phone,
  resident type, error, invitation id once sent).
- Permissions: `SOCIETY.ONBOARDING.VIEW/UPDATE` (SECRETARY and above), `SOCIETY.APPLICATION.VIEW/UPDATE`
  (platform, SUPER_ADMIN).

## API (planned)

- Public: `POST /api/society-applications` (rate-limited), `GET /api/society-applications/status?phone=&number=`.
- Platform: `GET /api/society-applications?status=`, `POST …/{id}/approve`, `POST …/{id}/reject` (reason).
- `POST /api/societies` gains optional `secretary {name, phone}`.
- `GET|PUT /api/societies/{id}/profile`.
- `GET /api/societies/{id}/onboarding` (steps, counts, what's missing, `canGoLive`), `POST …/go-live`.
- `POST /api/societies/{id}/structure/preview` and `…/structure/generate` (same body: wing patterns).
- `POST /api/societies/{id}/resident-imports` (multipart CSV, optional mapping), `GET …/resident-imports/current`,
  `DELETE …/rows/{rowId}`, `POST …/send` (now if Live, else at go-live) → links CSV / QR cards.

## Build steps

| Step | What | Done when |
|---|---|---|
| O1 | V23, status and source, profile API + notes, Setup restrictions (sign-in, invitations, pushes), onboarding checklist, go live | Tests: Setup blocks resident sign-in and pushes; go live needs the profile; existing societies unaffected |
| O2 | Platform create with secretary invitation; self-signup applications, approve/reject, rate limit | Tests: approval creates society + account + role atomically; existing-user applicant; duplicate pending refused |
| O3 | Bulk structure generator; resident sheet import, staging, validation, send (links CSV, QR data) | Tests: numbering patterns, skip existing, bad rows, flat limits, send at go-live |
| O4 | Web: register page + landing CTA, applications list, wizard with banner, structure generator, sheet import, go live | Unit tests, build |
| O5 | Postman folder, Playwright e2e for both paths on a fresh society, docs | Full Postman run; e2e green |

Each step ends with the suite passing and its own commit.

## Open questions

1. **Self-signup approval.** Planned as "waits for super-admin approval". Instant self-signup needs OTP
   and abuse limits; worth it only with subscriptions. OK to keep approval?
2. **Setup restrictions.** Planned: residents can't sign in or get pushes until Go live. Or should Setup
   only be a checklist with no restrictions?
3. **Bulk delivery.** CSV of links + printable QR cards for now. Is an SMS/WhatsApp provider on the
   roadmap (it would also give OTP)?
4. **Self-signup on/off.** Planned as a deployment setting (`app.onboarding.self-signup-enabled`, default
   on) since there is no platform-level settings page yet. Fine?

## Not in this round

- Suspending a society, plans and payments (subscription, module 30).
- OTP / SMS / WhatsApp delivery.
- Excel files directly (save as CSV).
- Moving a society between complexes during onboarding.
