# Society Management & Structure (`society`)

Complexes, societies, wings and flats. Status: ✅ built (M1). Modules 5 and 6 share this package.

## Rules
- Creating or deleting a society is platform-level (SUPER_ADMIN). Everything else is checked per
  society, so an ADMIN/SECRETARY only sees and edits societies they hold a role in.
- Society-wide roles see every wing and flat; flat-tied roles (RESIDENT) see only their own flats and
  the wings those are in.
- Flat numbers are unique within a wing.
- Deletes are refused (409) while history (visitors, complaints, bills…) references the record.
- Complex endpoints are platform-only until it is decided what a complex shares.
- A flat has an optional `areaSqft`, used by per-sq-ft maintenance charges (see [maintenance](maintenance.md)).

## Endpoints

| Method | Path | Permission |
|---|---|---|
| POST | `/api/complexes` | `COMPLEX.SETTINGS.UPDATE` |
| GET | `/api/complexes`, `/api/complexes/{id}` | `COMPLEX.SETTINGS.VIEW` |
| PATCH | `/api/complexes/{id}` | `COMPLEX.SETTINGS.UPDATE` |
| DELETE | `/api/complexes/{id}` | `COMPLEX.SETTINGS.DELETE` |
| POST | `/api/societies` | `SOCIETY.SETTINGS.CREATE` (platform) |
| GET | `/api/societies`, `/api/societies/{id}` | `SOCIETY.SETTINGS.VIEW` |
| PATCH | `/api/societies/{id}` | `SOCIETY.SETTINGS.UPDATE` |
| DELETE | `/api/societies/{id}` | `SOCIETY.SETTINGS.DELETE` (platform) |
| POST | `/api/societies/{societyId}/wings` | `FLAT.STRUCTURE.CREATE` |
| GET | `/api/societies/{societyId}/wings`, `/api/wings/{id}` | `FLAT.DIRECTORY.VIEW` |
| PATCH / DELETE | `/api/wings/{id}` | `FLAT.STRUCTURE.UPDATE` / `.DELETE` |
| POST | `/api/wings/{wingId}/flats` | `FLAT.STRUCTURE.CREATE` |
| GET | `/api/wings/{wingId}/flats`, `/api/flats/{id}` | `FLAT.DIRECTORY.VIEW` |
| PATCH / DELETE | `/api/flats/{id}` | `FLAT.STRUCTURE.UPDATE` / `.DELETE` |
| GET | `/api/societies/{societyId}/settings` | `SOCIETY.SETTINGS.VIEW` |
| PATCH | `/api/societies/{societyId}/settings` | `SOCIETY.SETTINGS.UPDATE` (society-wide) |

## Society settings
Per-society rules that a society can change, so behaviour isn't hard-coded (SECRETARY and above).
- **Definitions in code:** `SocietySettingCatalog` lists every setting: key, group (page section),
  label, help text, type (`BOOLEAN` / `INTEGER` / `TEXT` / `CHOICE`), default, and limits (min/max,
  max length, options).
- **Values in `society_setting`:** one row per setting a society has changed; no row = default.
- `GET` returns every setting, grouped in display order, with `value`, `defaultValue`, `customized`,
  limits and `updatedAt` / `updatedById`. The web page draws itself from this response.
- `PATCH` takes only the keys that change, e.g. `{"tenantsSeeMaintenance": true}`. `null` resets a key to
  its default. Unknown keys, wrong JSON types and out-of-range values reject the whole request (400).
- Other modules read values with `SocietySettingsService.boolean/integer/text(societyId, key)`. These are
  cached per society (`societySettings`, Caffeine); a change evicts the cache now and again after commit.
- **To add a setting:** add a definition (and a `SocietySettingKeys` constant if code reads it). No
  migration, no web change.
- **Change event:** a `PATCH` that changes any effective value publishes `SocietySettingsChanged`
  (society, user, old and new value per key) inside its transaction. Listeners run in that transaction:
  finance audits its settings there, and refuses (400, whole change rolled back) to move the financial
  year once the books are started.

| Key | Group | Type | Default | Effect |
|---|---|---|---|---|
| `tenantsSeeMaintenance` | Maintenance | BOOLEAN | false | Off: only a flat's OWNER / FAMILY residents see its bills, payments and dues and get maintenance pushes. On: TENANTs too. See [maintenance](maintenance.md). |
| `inviteExpiryDays` | Membership & invitations | INTEGER (1–30) | 7 | How long an invitation link works; resending starts it again. See [invitation](invitation.md). |
| `residentsCanInvite` | Membership & invitations | BOOLEAN | true | Owners invite family and tenants, tenants invite tenants, onto their own flat. |
| `residentInvitesNeedApproval` | Membership & invitations | BOOLEAN | true | A resident's invitation works only after a committee member approves it. |
| `maxResidentsPerFlat` | Membership & invitations | INTEGER (0–100) | 10 | Residents plus open invitations per flat, for any invitation; 0 = no limit. |
| `financialYearStartMonth` | Finance & accounts | INTEGER (1–12) | 4 | Month the financial year starts; fixed once the books are started. See [finance](finance.md). |
| `financeApprovalAbove` | Finance & accounts | INTEGER (≥ 0) | 0 | Rupees; expenses, journals and refunds above it need a second person's approval; 0 = never. |
| `residentsSeeFinanceSummary` | Finance & accounts | BOOLEAN | false | Residents see the monthly income/expense summary, never individual entries. |

## Tables
`complex`, `society`, `wing`, `flat`, `society_setting` (V16). `parking_slot` and `staff_member` exist
as schema only (Phase 2).
