# Notices (`notice`)

Society notice board. Status: ✅ built (M4, push 2026-10-30). Module 22 in [MODULES.md](../MODULES.md);
the name `communication` is kept for future in-app messaging.

## Who may do what (within one society)
- Everyone with a role in the society reads its notice board.
- COMMITTEE and above post, and edit/delete their own notices.
- Holders of `NOTICE.POST.MODERATE` (SECRETARY and above) edit/delete anyone's.

## Endpoints

| Method | Path | Permission |
|---|---|---|
| POST | `/api/societies/{societyId}/notices` | `NOTICE.POST.CREATE` |
| GET | `/api/societies/{societyId}/notices` | `NOTICE.POST.VIEW` (newest first) |
| GET | `/api/notices/{id}` | `NOTICE.POST.VIEW` |
| PATCH | `/api/notices/{id}` | `NOTICE.POST.UPDATE` (own) or `NOTICE.POST.MODERATE` |
| DELETE | `/api/notices/{id}` | `NOTICE.POST.DELETE` (own) or `NOTICE.POST.MODERATE` |

## Push events
`NOTICE_POSTED` (`noticeId`) to everyone in the society except the author.

## Tables
`notice` (`updated_at` / `updated_by` record edits).
