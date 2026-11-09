# Invitations (`invitation`)

Bringing people into a society with a role, following the role hierarchy. Status: ✅ built
(2026-11-09: V17, V18; backend and web).

## Decisions
| # | Decision |
|---|---|
| 1 | Delivery: the server returns a link, the inviter shares it (WhatsApp, SMS, anything). Real SMS/WhatsApp sending comes later without changing the flow. |
| 2 | Residents can invite people onto **their own flat** in v1. |
| 3 | Owner vs tenant uses the existing `user_role.resident_type` (OWNER / FAMILY / TENANT). No new roles. |
| 4 | Maintenance bills are for the flat's **owner side** (OWNER + FAMILY). Tenants don't see them, by default. A society can turn on tenant access (setting below). |

## Who can invite whom
Rule: you can invite a role **strictly below your own**, in a society where you hold the role
(`Access.canGrant`, already exists). Residents are the one exception, scoped to their own flat.

| Inviter | Can invite |
|---|---|
| SUPER_ADMIN | ADMIN of any society (how a new society gets its first admin) |
| ADMIN | SECRETARY, COMMITTEE, GUARD, RESIDENT (any flat, any resident type) |
| SECRETARY | COMMITTEE, GUARD, RESIDENT (any flat, any resident type) |
| COMMITTEE | RESIDENT (any flat, any resident type) |
| RESIDENT · OWNER | RESIDENT on own flat as FAMILY or TENANT |
| RESIDENT · TENANT | RESIDENT on own flat as TENANT (their household; keeps them off the bills) |
| RESIDENT · FAMILY | Nobody |
| GUARD | Nobody |

Custom roles follow the same rank rule automatically.

## Flow
1. **Create.** The inviter gives phone, name, role, plus flat and resident type when the role needs a
   flat. The server checks rank, society, flat and limits, then stores the invite `PENDING` with a
   random token (only its hash is stored). The token expires after `inviteExpiryDays`.
2. **Share.** The response has the link `/invite/{token}`. If the phone already has an account, that
   user also gets a push and an in-app "pending invites" entry.
3. **Open.** `GET /api/invitations/token/{token}` (public) shows the society, flat, role, and who sent
   the invite. Phone is masked (`98•••••210`).
4. **Accept.**
   - New phone: set name and password. The account and role are created in one transaction.
   - Existing account: log in, then accept. The logged-in phone must match the invite's phone.
5. **Approval (resident invites only, when the setting is on) comes first.** A resident's invitation is
   created `AWAITING_APPROVAL`: the resident can already share the link, but it only previews ("waiting
   for approval") until COMMITTEE or above approves it; then it works like any other and its expiry
   starts. A rejected invitation never works, so a rejected person never gets an account. (Confirmed
   with the product owner on 2026-11-09.)
6. **Done.** Status `ACCEPTED`, role active, inviter gets a push. Each token works once.

Statuses: `PENDING → ACCEPTED | DECLINED | REVOKED | EXPIRED`, plus
`AWAITING_APPROVAL → PENDING (approved) | REJECTED | REVOKED` for resident invites needing approval.

## Rules (as built)
- **Who (`InvitationRules`):** staff: `USER.INVITE.CREATE` (COMMITTEE, inherited upwards) plus a role
  above the invited one in that society (`Access.canGrant`). Residents (when `residentsCanInvite`): a
  holder of a flat role invites to the same role on the same flat: OWNER (or no type) as FAMILY or
  TENANT, TENANT as TENANT, FAMILY nobody. Only society-scope roles can be invited (403 / 400 otherwise).
- **Approval:** with `residentInvitesNeedApproval` (default on) a resident's invitation is created
  `AWAITING_APPROVAL`: its link previews but can't be accepted. A society-wide `USER.INVITE.APPROVE`
  holder (COMMITTEE+) approves (→ `PENDING`, expiry starts then, `approvedBy/At` kept) or rejects
  (→ `REJECTED`). Approval re-checks that the resident may still invite. Staff invitations never wait.
- **Flat limit:** `maxResidentsPerFlat` (default 10, 0 = none) caps active residents of the flat plus its
  open invitations, for every invitation to a flat (staff too), at create and renew.
- **Shape:** flat-tied roles need `flatId` (in the society) and `residentType`; other roles must have
  neither (400). Phone: 10–14 digits, optional leading `+`, trimmed.
- **One open invitation** per phone + society + role + flat (unique index). Inviting again, by anyone
  allowed, closes the open one (`REVOKED`, or `EXPIRED` if it had run out) and issues a new link.
- **Refused (409)** if the phone's account already holds that role there, or the account is deactivated.
- **Accept:** a new phone signs up with a password (8–72) and optionally corrects the name; the account
  and role are created together, then the person logs in normally. A phone that already has an account
  must log in and accept (by link or from `/mine`); the logged-in phone must match (403 otherwise).
- **The inviter's rights are checked again at accept time.** If they lost the role or were
  deactivated, accepting fails (409) and the society has to send a new invitation.
- **Revoke / resend:** residents only their own; staff any they could have sent. Resend works while
  `PENDING`, `AWAITING_APPROVAL` (stays waiting) or `EXPIRED` ("Renew link"; an expired resident
  invitation that was never approved goes back to the committee), unless the person has a newer open
  invitation for the same role and flat (409). It gives a new token and a fresh expiry; the old link
  stops working.
- **Expiry:** `inviteExpiryDays` society setting (1–30, default 7). A `PENDING` invitation past its expiry
  counts as `EXPIRED` everywhere (preview, lists, accept) even before its row changes.
- **Tokens:** 32 random bytes (URL-safe base64). Only the SHA-256 is stored, so the link can't be shown
  again later: "show QR again" in the web app is a resend.
- **Identity proof** in v1 is the link alone (bound to the phone). OTP on accept comes with SMS delivery.
- **Pushes** (after commit, best effort): `INVITATION_RECEIVED` to the invited phone's account if it has
  one (on create, or on approval for resident invitations); `INVITATION_ACCEPTED` back to the inviter;
  `INVITATION_NEEDS_APPROVAL` to every society-wide approver; `INVITATION_APPROVED` /
  `INVITATION_REJECTED` back to the resident.

## Invite QR code
Every invite comes with a QR code of its link, so the person can scan it with their phone camera and
land straight on the accept page.
- The backend returns the full link (`inviteUrl`, built from a new `app.web-base-url` config). The web
  app draws the QR from it with the existing `qrcode` library (same as the UPI QR on bills), so no
  image is stored or generated on the server.
- Invite dialog after creating: **QR code + Copy link + Share on WhatsApp + Download QR (PNG) +
  Print card**. The printable card shows society name, flat, role, "Scan to join", the QR, the expiry
  date and the short link underneath (for people who can't scan).
- From the Invitations list, while the invite is `PENDING`, "Show QR" resends it and shows the new QR
  (the old one stops working), because only the token's hash is stored.
- The accept page `/invite/:token` must work well on a phone, since that is where a scan lands.
- The QR holds only the link and token: no phone, name or other personal data.
- Later (not v1): a **society join QR** posted at the gate/notice board for self-registration that a
  committee member approves. It would be a separate feature, with its own settings toggle.

## Society settings (new, extensible, per society)
A general **Society settings** feature, not only for invitations: more options will be added over
time, so adding one must be cheap.

Design:
- **Definitions live in code**: a `SocietySettingDefinition` registry (key, group, label, help text,
  type BOOLEAN / INTEGER / TEXT / CHOICE, default, min/max, max length or allowed values). Built as
  `SocietySettingCatalog`. Every setting is edited with `SOCIETY.SETTINGS.UPDATE`; a per-setting
  permission can be added if one ever needs a different role.
- **Values live in one table** `society_setting` (society_id, key, value, updated_at, updated_by),
  with a row only when a society changes a setting from its default.
- `GET /api/societies/{id}/settings` returns every definition merged with the society's values,
  grouped (Membership & invitations, Maintenance, …). The web page draws the form from this response,
  so a **new setting shows up on the page without web changes**.
- `PATCH` takes only the keys that changed, validates them against their definitions and records who
  changed what. Unknown keys get a 400.
- Modules read settings through one `SocietySettings` service (`getBoolean(societyId, key)`, …),
  cached like roles.
- To add a setting later: one definition in code, plus a migration only if it needs a new permission.
- `maintenance_settings` stays as it is for now and can move into this later.

First settings:

| Setting | Default | Meaning |
|---|---|---|
| `tenantsSeeMaintenance` | **false** (toggle) | Off: only OWNER and FAMILY see the flat's bills, payments and dues, and get bill/reminder pushes. On: tenants do too. |
| `residentInvitesNeedApproval` | true (toggle) | Invites sent by residents wait for COMMITTEE+ approval. |
| `residentsCanInvite` | true (toggle) | Turns resident-sent invites off entirely. |
| `maxResidentsPerFlat` | 10 | Limit on active + pending residents per flat (0 = no limit). |
| `inviteExpiryDays` | 7 (1–30) | How long an invite link works. ✅ built (V17 step). |

`tenantsSeeMaintenance` needs a maintenance-side change: bill/payment/dues reads and
`MaintenanceNotifications.tokensByFlat` filter flat roles by resident type when the toggle is off.
Society-wide viewers (COMMITTEE+) are unaffected.

## Endpoints
| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/societies/{societyId}/invitations` | staff by rank, or a resident onto their own flat | `{phone, name, role, flatId?, residentType?}` → 201 `{invitation, token, inviteUrl}` |
| GET | `/api/societies/{societyId}/invitations/roles` | `USER.INVITE.CREATE` | Roles the caller may invite to there (the invite dialog's role picker) |
| GET | `/api/societies/{societyId}/invitations/flat-options` | logged in | A resident's own flats: `[{flatId, flatLabel, role, residentTypes, needsApproval}]` |
| GET | `/api/societies/{societyId}/invitations?status=&page=&size=` | `USER.INVITE.VIEW` society-wide: all; other members: their own | Newest first |
| GET | `/api/invitations/{id}` | `USER.INVITE.VIEW` society-wide, or the inviter | |
| POST | `/api/invitations/{id}/resend` | staff who could have sent it / the resident who did | → `{invitation, token, inviteUrl}` |
| POST | `/api/invitations/{id}/revoke` | inviter / staff who could have sent it | |
| GET | `/api/invitations/mine` | logged in | Open invitations for my phone |
| POST | `/api/invitations/{id}/accept` · `/decline` | logged in, phone matches | |
| GET | `/api/invitations/token/{token}` | public | Preview: society, role, flat, inviter, masked phone, status, `hasAccount` |
| POST | `/api/invitations/token/{token}/accept` | public | Logged in → adds the role to that account; otherwise `{name?, password}` creates it |
| POST | `/api/invitations/token/{token}/decline` | public | |
| POST | `/api/invitations/{id}/approve` · `/reject` | `USER.INVITE.APPROVE` society-wide | Resident invitations `AWAITING_APPROVAL` |
| GET · PATCH | `/api/societies/{societyId}/settings` | SECRETARY+ (`SOCIETY.SETTINGS.VIEW` / `.UPDATE`) | ✅ step 1, see [society](society.md#society-settings) |

Permissions on COMMITTEE (SECRETARY, ADMIN inherit; SUPER_ADMIN has all): `USER.INVITE.CREATE`,
`USER.INVITE.VIEW` (V17), `USER.INVITE.APPROVE` (V18). Errors: 400 bad shape, 403 rank / scope /
wrong phone, 404 unknown link, 409 already answered / expired / already holds the role / inviter lost
the right.

Config: `app.web-base-url` (`WEB_BASE_URL`, required in prod) builds `inviteUrl`.

## Tables
- `invitation` (V17): society_id, role_id, flat_id?, resident_type?, phone, name, token_hash (unique),
  status, invited_by, created_at, expires_at, closed_at?, closed_by?, accepted_user_id?. Goes with its
  society, role or flat (cascade); partial unique index on open invitations. V18 adds approved_at,
  approved_by.
- `society_setting` (V16): society_id, setting_key (PK together), setting_value, updated_at,
  updated_by. Cascades with the society.

Code: `InvitationService` (rules), `InvitationTokens` (token, hash, phone mask), `InvitationController`,
`InvitationNotificationListener`, `InvitationRules` (who may invite). Tests: `InvitationIntegrationTest`
(12), `ResidentInvitationIntegrationTest` (9).

## Web screens (planned)
- **Invite** dialog (members page / flat page): role list from `/api/roles/assignable`, flat + type
  when needed, then the QR code with Copy link / Share on WhatsApp / Download QR / Print card.
- **Invitations** page: pending / awaiting approval / history, with show QR, resend, revoke, approve.
- **My flat → Invite** for residents (same dialog, limited to their flat).
- **Accept page** `/invite/:token`: phone-friendly; preview, then sign up or log in and accept.
- **Society settings** page drawn from the settings response, grouped, with toggles and inputs.

## Build order
1. Society settings framework (registry, table, GET/PATCH, cache, web page) + `tenantsSeeMaintenance`
   applied to maintenance. **✅ 2026-11-09** (V16, web rules card). The other membership settings
   (`residentsCanInvite`, `residentInvitesNeedApproval`, `maxResidentsPerFlat`) are added to the
   catalog together with step 4, which uses them.
2. Invitation core: create, preview, accept (new + existing user), revoke, resend, `inviteUrl`.
   **Backend ✅ 2026-11-09** (V17, `inviteExpiryDays`).
3. Web: invite dialog with QR / share / download / print, accept page, invitations page.
   **✅ 2026-11-09** (web `feature/invitations`): Invitations page (status filters, Show QR = resend,
   Renew link for expired, cancel), invite dialog (role picker from `/invitations/roles`, then QR with
   Copy link / WhatsApp / Share / Download QR / Print card), public `/invite/:token` page (sign up, or
   sign in and accept, or decline), a pending-invitations banner over every page for signed-in users,
   "Invite someone" in the command palette, push routes for `INVITATION_RECEIVED` / `INVITATION_ACCEPTED`.
4. Resident invites + approval (uses the membership settings). **✅ 2026-11-09** (V18; web: "Invite to my
   flat" for residents, Awaiting approval filter with Approve / Reject, approval note on the QR).
5. Postman folder, docs.
