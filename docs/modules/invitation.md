# Invitations (`invitation`)

Bringing people into a society with a role, following the role hierarchy. Status: 📝 planned
(2026-11-09, replaces the earlier blocked design).

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
5. **Approve (resident invites only, when the setting is on).** The invite goes to `AWAITING_APPROVAL`.
   COMMITTEE or above in that society approves or rejects it. The role is attached on approval.
6. **Done.** Status `ACCEPTED`, role active, inviter gets a push. Each token works once.

Statuses: `PENDING → ACCEPTED | DECLINED | REVOKED | EXPIRED`, plus
`PENDING → AWAITING_APPROVAL → ACCEPTED | REJECTED` for resident invites needing approval.

## Rules
- One open invite per phone + society + role + flat. Re-inviting replaces the old token.
- Rejected if the phone already holds that role in that society/flat.
- Revoke: the inviter, or anyone who outranks them in the society. Resend: new token, old one dies.
- An invite checks the inviter's rights **again at accept time**. If the inviter lost their role,
  the invite fails.
- Identity proof in v1 is the link alone (bound to the phone). OTP on accept comes with SMS delivery.
- Phone stored in the same format as `users.phone` today.
- Expired invites are marked by a daily job (or lazily on read).

## Invite QR code
Every invite comes with a QR code of its link, so the person can scan it with their phone camera and
land straight on the accept page.
- The backend returns the full link (`inviteUrl`, built from a new `app.web-base-url` config). The web
  app draws the QR from it with the existing `qrcode` library (same as the UPI QR on bills), so no
  image is stored or generated on the server.
- Invite dialog after creating: **QR code + Copy link + Share on WhatsApp + Download QR (PNG) +
  Print card**. The printable card shows society name, flat, role, "Scan to join", the QR, the expiry
  date and the short link underneath (for people who can't scan).
- Shown again from the Invitations list while the invite is `PENDING`. Resend gives a new QR (the old
  one stops working).
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
| `inviteExpiryDays` | 7 | How long an invite link works. |

`tenantsSeeMaintenance` needs a maintenance-side change: bill/payment/dues reads and
`MaintenanceNotifications.tokensByFlat` filter flat roles by resident type when the toggle is off.
Society-wide viewers (COMMITTEE+) are unaffected.

## Endpoints (planned)
| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/societies/{societyId}/invitations` | rank rule above | `{phone, name, role, flatId?, residentType?}` → invite + link |
| GET | `/api/societies/{societyId}/invitations?status=` | COMMITTEE+ (all), resident (own sent) | |
| POST | `/api/invitations/{id}/resend` | inviter / outranks | New token |
| POST | `/api/invitations/{id}/revoke` | inviter / outranks | |
| POST | `/api/invitations/{id}/approve` · `/reject` | COMMITTEE+ in society | Resident invites only |
| GET | `/api/invitations/token/{token}` | public | Preview |
| POST | `/api/invitations/token/{token}/accept` | public (new user) / logged in (existing) | `{name, password}` for new users |
| POST | `/api/invitations/token/{token}/decline` | public | |
| GET | `/api/users/me/invitations` | authenticated | Pending invites for my phone |
| GET · PATCH | `/api/societies/{societyId}/settings` | SECRETARY+ (`SOCIETY.SETTINGS.VIEW` / `.UPDATE`) | ✅ built (step 1), see [society](society.md#society-settings) |

New permissions: `USER.INVITE.CREATE`, `USER.INVITE.VIEW`, `USER.INVITE.APPROVE`. Settings reuse the
existing `SOCIETY.SETTINGS.VIEW` / `.UPDATE`. Residents never read the settings directly: the invite
endpoints apply them.

## Tables (planned, `V16__invitations.sql`)
- `invitation`: id, society_id, role_id, flat_id?, resident_type?, phone, name, token_hash, status,
  invited_by, expires_at, created_at, accepted_by?, accepted_at?, decided_by?, decided_at?.
- `society_setting` (✅ V16): society_id, setting_key (PK together), setting_value, updated_at,
  updated_by. Cascades with the society.

## Web screens (planned)
- **Invite** dialog (members page / flat page): role list from `/api/roles/assignable`, flat + type
  when needed, then the QR code with Copy link / Share on WhatsApp / Download QR / Print card.
- **Invitations** page: pending / awaiting approval / history, with show QR, resend, revoke, approve.
- **My flat → Invite** for residents (same dialog, limited to their flat).
- **Accept page** `/invite/:token`: phone-friendly; preview, then sign up or log in and accept.
- **Society settings** page drawn from the settings response, grouped, with toggles and inputs.

## Build order
1. Society settings framework (registry, table, GET/PATCH, cache, web page) + `tenantsSeeMaintenance`
   applied to maintenance. **Backend ✅ 2026-11-09** (V16); web page pending. The membership settings
   (`residentsCanInvite`, `residentInvitesNeedApproval`, `maxResidentsPerFlat`, `inviteExpiryDays`) are
   added to the catalog together with the features that use them.
2. Invitation core: create, preview, accept (new + existing user), revoke, resend, `inviteUrl`.
3. Web: invite dialog with QR / share / download / print, accept page, invitations page.
4. Resident invites + approval (uses the membership settings).
5. Postman folder, docs.
