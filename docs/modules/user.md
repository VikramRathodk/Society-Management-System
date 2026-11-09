# User Management (`user`)

User identity, role assignments, profile and passwords. Status: ✅ built (M1).

## Rules
- A user is identified by a unique phone number and is always created with a first role assignment.
- Higher roles manage only users whose every role is below theirs, in the scope they hold it
  (`Access.canManage`). Editing your own profile needs only `USER.PROFILE.UPDATE`.
- Residency is a role assignment with a flat (`user_role.flat_id`, `resident_type` OWNER / FAMILY / TENANT).
- Deactivation blocks login and every request. Deletion is platform-only and refused (409) while the
  user has history.
- Passwords: 8–72 characters (BCrypt reads 72 bytes). Change own password with the current one; an admin
  can reset a lower-ranked user's password.
- One FCM push token per user (`fcm_token`); registering a token moves it off any other account.
- **First super admin:** `BootstrapAdmin` creates one from `BOOTSTRAP_ADMIN_*` when the users table is
  empty (see README).

## Endpoints

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/api/users/me` | authenticated | Own profile, roles (each with its `societyName`) and effective permissions |
| POST | `/api/users` | `USER.ACCOUNT.CREATE` + rank | Create with first role |
| GET | `/api/users` | `USER.ACCOUNT.VIEW` | Users in caller's societies; `?societyId=` narrows |
| GET | `/api/users/{id}` | `USER.ACCOUNT.VIEW` | |
| PATCH | `/api/users/{id}` | `USER.PROFILE.UPDATE` (own) / `USER.ACCOUNT.UPDATE` + rank | name, email, fcmToken (blank clears) |
| POST | `/api/users/me/password` | `USER.PROFILE.UPDATE` | `{currentPassword, newPassword}` → 204 |
| POST | `/api/users/{id}/password` | `USER.ACCOUNT.UPDATE` + rank | `{newPassword}` → 204; not for own account |
| PATCH | `/api/users/{id}/deactivate` | `USER.ACCOUNT.DELETE` + rank | |
| PATCH | `/api/users/{id}/activate` | `USER.ACCOUNT.DELETE` + rank | |
| DELETE | `/api/users/{id}` | `USER.ACCOUNT.DELETE` (platform) | 409 while history exists |
| GET | `/api/users/{id}/roles` | `USER.ROLE.VIEW` | |
| GET | `/api/users/{id}/permissions` | `USER.ROLE.VIEW` | Effective permissions |
| POST | `/api/users/{id}/roles` | `USER.ROLE.ASSIGN` + rank | |
| DELETE | `/api/users/{id}/roles/{assignmentId}` | `USER.ROLE.DELETE` + rank | |

## Tables
`users`, `user_role`.

## Known limits
- One push token per user, so a user on web and phone gets pushes on the last registered device only.
- Self-registration without an invitation isn't built; people join through [invitations](invitation.md).
