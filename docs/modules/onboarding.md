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

## Progress

- **O1 done (2026-11-14, f1b590a).** V23 adds status/source/went-live and the profile columns to
  `society`. `PATCH /api/societies/{id}` takes the whole profile (blank clears a field; PIN, phone and
  email validated); `PUT|GET|DELETE /api/societies/{id}/logo` (JPEG/PNG/WebP, 1 MB, in photo storage);
  `GET /api/societies/{id}/onboarding` (profile, structure, residents, committee, settings, `canGoLive`);
  `POST /api/societies/{id}/go-live`. Built differently from the plan above:
  - Setup is enforced by refusing **flat roles** (account or invitation) until go-live, so residents
    can't sign in or be pushed to because none exist yet; society-wide roles work as normal.
  - Profile and go-live use the existing `SOCIETY.SETTINGS.VIEW/UPDATE` permissions (secretary and
    above, or the platform); no new onboarding permissions.
  - `POST /api/societies` creates in Setup; `startLive: true` skips it (moving in a running society).
    Societies created in code (seeding, tests) default to Live.
  - Logo is `logo_key` in photo storage. Go live needs address, city, state, PIN and contact phone.

- **O2 done (2026-11-14).** V24 adds `society_application` and `SOCIETY.APPLICATION.VIEW/UPDATE`
  (platform only; SUPER_ADMIN holds every permission).
  - Platform-led: `POST /api/onboarding/societies` `{name, address?, complexId?, startLive?, secretary {name, phone}}`
    creates the society and a SECRETARY invitation in one transaction and returns `{society, secretaryInvitation}`
    (token + link, as for any invitation). It lives in the onboarding module because `society` may not use
    `invitation`; plain `POST /api/societies` is unchanged.
  - Public, under `/api/society-applications/public`: `GET /enabled`, `POST` (register; a signed-in caller
    applies with their own account), `GET /status?number=&phone=`.
  - Platform: `GET /api/society-applications?status=` (pending first, oldest at the top), `GET /{id}`,
    `POST /{id}/approve`, `POST /{id}/reject {reason}`.
  - Approving checks everything first, then creates the society (Setup, `SELF_SIGNUP`, city and contact
    phone/email from the form), the account from the stored password hash (or adds the role to the signed-in
    applicant's account) and the SECRETARY role. The hash is dropped once decided. If the phone has got an
    account since applying, approval is refused; reject and ask them to apply signed in.
  - A phone with an account must sign in to apply; one pending registration per phone (also a partial
    unique index); 5 registrations per address per hour (`app.onboarding.applications-per-ip-per-hour`,
    answered 429); `app.onboarding.self-signup-enabled` (default on) closes registration.
  - No push on approval (onboarding doesn't use the notification module); the applicant checks the
    status page, and the super admin's confirmation call tells them.

- **O3 done (2026-11-14).** V25 adds `resident_import` (one OPEN sheet per society) and `resident_import_row`.
  - Wings & flats: `POST /api/societies/{id}/structure/preview` and `…/generate`, body
    `{wings: [{wing, floorsFrom, floorsTo, flatsPerFloor, numbering?, skip?, extraUnits?}]}` (needs
    `FLAT.STRUCTURE.CREATE`). Numbering tokens `{floor}`, `{nn}`, `{n}`, `{wing}` (default `{floor}{nn}`);
    floors 0–200, up to 50 per floor, 5,000 flats a request. A numbering that repeats a number (e.g.
    `{floor}{n}` with 11 flats a floor gives 111 twice) is refused. Wings match by name ignoring case;
    existing flats are skipped, so generating twice is harmless. The answer lists every flat with `exists`.
  - Residents sheet: `POST /api/societies/{id}/resident-imports` (multipart `file`, optional 1-based
    `flatColumn`, `nameColumn`, `phoneColumn`, `wingColumn`, `typeColumn`; otherwise guessed from the
    header) replaces the unsent sheet and returns it with the header and columns used; `GET …/current`
    (204 when none), `DELETE …/current/rows/{rowId}`, `DELETE …/current`, `POST …/current/send`.
    Secretary and above (`SOCIETY.SETTINGS.UPDATE`); 1 MB, 2,000 rows. The CSV reading moved to
    `common/csv/CsvReader` (bank statements use it too).
  - Rows are checked again every time they are read or sent: name, phone (Indian numbers kept as their
    10 digits, so `+91 98765-43210` matches the account), type (owner/tenant/family in plain words; blank
    is owner), flat (a wing column, `A-101` in the flat cell, or the number alone when only one wing has
    it), same person and flat twice, already living there, deactivated account, and the per-flat limit
    counting residents, open invitations and earlier rows. **The same phone on different flats is allowed**
    (owners of two flats), unlike the plan's "duplicate phone".
  - Sending creates a RESIDENT invitation per good row through the invitation module; every check it could
    refuse on is made first, so bad rows are skipped instead of failing the batch. In Setup `send` answers
    409 and the sheet goes out at go-live: `POST /go-live` now returns `{onboarding, residentInvitations}`
    (null when nothing was staged). Each sent row carries its token and link: the web builds the links CSV
    and QR cards from it (O4), since the links exist only in that response.
  - The checklist's `residents.staged` counts rows ready to send; a staged sheet completes the step.

- **O4 done (2026-11-15, web `feature/onboarding` 8752175, on top of 48507ef, which commits the UI polish
  that had been waiting uncommitted on master).**
  - New society (Societies page) asks for the secretary's name and phone too, plus "already running:
    skip setup", calls `POST /api/onboarding/societies`, then shows the secretary's link and QR (only
    "Done" closes it). The list shows Setting up / Live and a Setup link per society.
  - `/register-society` (public, signed in or not): register, or check a registration by number and
    phone; a signed-in applicant registers with their account, and "Open my society" reloads their roles
    once approved. The landing page links to it while `GET …/public/enabled` says registration is open.
  - `/registrations` (platform, `SOCIETY.APPLICATION.VIEW`, in the side nav): waiting first, approve after
    a confirm, reject with a reason.
  - `/societies/:id/setup` (`SOCIETY.SETTINGS.UPDATE`): step rail with done/required marks and five
    panels: profile (shared form with logo and notes, also on the settings page now), wings & flats
    (pattern form with a live sample, preview, create), residents (upload, column pickers, row filter,
    leave rows out; Send in a live society, staged in Setup), committee & rules (invite dialog, the
    society rules, links to maintenance and finance settings), go live (checklist, confirm, then the
    sent links). `?step=` keeps the open step. Opening it selects that society in the switcher.
  - After sending (go-live or Send): links CSV (UTF-8 with BOM for Excel, formula cells defused) and a
    print page of QR cards, three to a row, sorted by flat; lost links are resent from Invitations.
  - A banner above every page while the selected society is in Setup and the user can run its setup,
    with "n of 4 steps done" and Continue (hidden on the setup page).
  - Web tests: 194 pass (35 new); production build passes.

## Open questions

All answered 2026-11-14 as planned: self-signup waits for the super admin's approval; Setup restricts flat
roles (as built in O1); bulk delivery is links CSV + QR cards (no SMS/WhatsApp provider yet); self-signup
is a deployment property.

## Not in this round

- Suspending a society, plans and payments (subscription, module 30).
- OTP / SMS / WhatsApp delivery.
- Excel files directly (save as CSV).
- Moving a society between complexes during onboarding.
