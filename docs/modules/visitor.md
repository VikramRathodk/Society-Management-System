# Visitor Management (`visitor`)

Gate log with resident approval. Status: ✅ built (M3, push 2026-10-30).

## Flow
Guard logs a visitor (name, purpose, flat, optional photo) → every active resident of the flat gets a
push → the first resident to approve or reject wins (atomic conditional update; a second answer gets
409) → the guard gets a push and marks the visitor entered, then exited.

Statuses: `PENDING → APPROVED | REJECTED`, `APPROVED → ENTERED → EXITED`.

## Who sees and does what
- Gate roles (GUARD and above) log visitors and mark them entered/exited.
- Guards see what they logged plus every still-active entry, so the next shift can take over.
- Residents see only their own flat's entries and approve/reject them.
- COMMITTEE and above (`VISITOR.HISTORY.VIEW`) see the full gate log and export it.
- SECRETARY and above may approve/reject on a flat's behalf, recorded as an override
  (`VISITOR.APPROVAL.OVERRIDE`).

## Endpoints

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/api/visitors` (multipart) | `VISITOR.ENTRY.CREATE` | Photo up to 5 MB, stored in the database |
| GET | `/api/visitors?societyId=` | `VISITOR.ENTRY.VIEW` | Scoped as above; filters |
| GET | `/api/visitors/export?societyId=&from=&to=` | `VISITOR.ENTRY.EXPORT` | CSV |
| GET | `/api/visitors/{id}`, `/{id}/photo` | `VISITOR.ENTRY.VIEW` | |
| POST | `/api/visitors/{id}/approve` / `reject` | `VISITOR.APPROVAL.APPROVE` / `REJECT` | |
| POST | `/api/visitors/{id}/enter` / `exit` | `VISITOR.ENTRY.UPDATE` | |
| DELETE | `/api/visitors/{id}` | `VISITOR.ENTRY.DELETE` | |

## Push events
`VISITOR_LOGGED` (`visitorId`) to the flat's residents; `VISITOR_RESPONDED` (`visitorId`, `status`,
`override`) to the guard.

## Tables
`visitor_entry` (photo bytes inline).
