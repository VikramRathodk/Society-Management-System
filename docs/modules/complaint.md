# Complaints (`complaint`)

Flat-owned complaint tickets with a status workflow. Status: ✅ built (M5, push 2026-10-30).

## Flow
`OPEN → IN_PROGRESS → RESOLVED → CLOSED`. OPEN may go straight to RESOLVED, and a RESOLVED complaint may
be reopened (back to OPEN). CLOSED is final. Every step is kept in the complaint's timeline with an
optional note. Status changes are conditional updates, so two committee members can't both move it.

Categories: PLUMBING, ELECTRICAL, SECURITY, OTHER.

## Who sees and does what (within one society)
- Residents raise complaints for their own flat and see their flat's complaints.
- Only the author edits or withdraws a complaint, and only until it is resolved.
- COMMITTEE and above see every complaint, move it through its statuses and export.

## Endpoints

| Method | Path | Permission |
|---|---|---|
| POST | `/api/complaints` | `COMPLAINT.TICKET.CREATE` (own flat) |
| GET | `/api/complaints?societyId=&status=&category=&flatId=` | `COMPLAINT.TICKET.VIEW` (scoped) |
| GET | `/api/complaints/export?societyId=&from=&to=` | `COMPLAINT.TICKET.EXPORT` (CSV) |
| GET | `/api/complaints/{id}`, `/{id}/activity` | `COMPLAINT.TICKET.VIEW` |
| PATCH / DELETE | `/api/complaints/{id}` | `COMPLAINT.TICKET.UPDATE` / `.DELETE` (author) |
| POST | `/api/complaints/{id}/start` / `resolve` / `close` | `COMPLAINT.RESOLUTION.APPROVE` (society-wide) |
| POST | `/api/complaints/{id}/reopen` | `COMPLAINT.RESOLUTION.REJECT` (society-wide) |

## Push events
`COMPLAINT_RAISED` (`complaintId`) to society-wide holders of `COMPLAINT.RESOLUTION.APPROVE`;
`COMPLAINT_STATUS_CHANGED` (`complaintId`, `status`) to the author, unless they made the change.

## Tables
`complaint`, `complaint_activity`.
