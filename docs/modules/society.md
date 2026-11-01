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

## Tables
`complex`, `society`, `wing`, `flat`. `parking_slot` and `staff_member` exist as schema only (Phase 2).
