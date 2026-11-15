# Meeting & governance (module 26)

How the society is run on the record: general body meetings (AGM, SGM) and managing committee
meetings, their notices and agendas, who attended and whether there was quorum, the resolutions passed
with their votes, and minutes approved and published to members. Planned 2026-11-15 on backend branch
`feature/governance`; build steps G1–G5 below. Reuses the chairman role, the approval pattern of
[policies](policy.md) and the attachment storage.

## Today

Meetings are announced as notices; attendance is a paper register; minutes are a PDF somebody keeps;
nobody can find which resolution approved what. The policy module left "a by-law change needs the
general body" for this module.

## Proposed decisions (to confirm, see "Open questions")

1. **Three kinds of meeting.** *AGM* and *SGM* (general body: every member flat is invited) and
   *committee meeting* (the managing committee: committee members, chairman, secretary). Each has a
   date and time, a place and/or an online link, and an ordered **agenda**.
2. **Life of a meeting.** DRAFT (committee prepares it) → **NOTICE SENT** (published to the invitees,
   with push; the notice period is checked) → **HELD** (attendance and outcomes recorded) →
   **MINUTES PUBLISHED**. It can be **CANCELLED** or **ADJOURNED** (to a new date, keeping its agenda and
   linking the two) before it is held.
3. **Members and quorum.** For general meetings one vote per **member flat** (its owner side; tenants
   attend but don't count), and quorum is counted in flats present. For committee meetings each
   committee member counts. The quorum rule is a per-society setting (default for general meetings:
   two thirds of member flats or 20, whichever is less — the usual model bye-law; for committee
   meetings: more than half). The secretary marks attendance; RSVP before the meeting is optional and
   only a headcount guide.
4. **Resolutions with recorded votes.** Each agenda item can carry a proposed resolution. At the
   meeting the secretary records proposer, seconder, votes for / against / abstaining (show of hands)
   and the outcome: PASSED, REJECTED or DEFERRED. Passed resolutions get a number (`AGM/2026-27/3`) and
   go into a searchable **resolutions register**. No online e-voting in this round.
5. **Minutes.** The secretary drafts minutes per agenda item after the meeting, with an optional signed
   PDF; the **chairman approves** them (the meeting's chairperson, or any CHAIRMAN-and-above who wasn't
   the drafter), and they are published: general meeting minutes to all members, committee meeting
   minutes to the committee only. Published minutes are never edited; a correction is a dated
   addendum.
6. **Links to other modules.** A resolution can reference a policy (e.g. "adopts version 3 of the
   Parking rules") and a published meeting can be announced on the notice board with one click; both
   are references only — they don't approve or publish anything by themselves.

## Who may do what (within one society)

| Action | Who | Permission |
|---|---|---|
| See general meetings, their agendas and published minutes; RSVP | everyone with a flat role | `GOVERNANCE.MEETING.VIEW` |
| See committee meetings and their minutes | COMMITTEE and above | `GOVERNANCE.COMMITTEE.VIEW` |
| Create and edit meetings and agendas, send the notice, cancel, adjourn | SECRETARY and above | `GOVERNANCE.MEETING.CREATE` / `UPDATE` |
| Record attendance, outcomes and votes; draft minutes | SECRETARY and above | `GOVERNANCE.MINUTES.CREATE` |
| Approve and publish minutes | CHAIRMAN and above, not the drafter | `GOVERNANCE.MINUTES.APPROVE` |

## Settings (new group "Meetings & governance")

- `agmNoticeDays` (14), `sgmNoticeDays` (7), `committeeNoticeDays` (2) — the shortest notice; sending
  later needs an explicit "short notice" reason, which is kept on the meeting.
- `generalQuorumPercent` (67) and `generalQuorumCap` (20) — quorum is the lesser of the percentage of
  member flats and the cap (cap 0 = no cap).
- `committeeQuorumPercent` (51).
- `meetingReminderHours` (24) — the reminder push before a meeting (0 = none).

## Data (V29, planned)

- `meeting`: society, kind (AGM | SGM | COMMITTEE), title, scheduled at, place, online link, status,
  notice sent at/by, short-notice reason, adjourned to (meeting id), cancelled reason, quorum required,
  quorum present (frozen when held), chairperson, created/updated by and at.
- `agenda_item`: meeting, position, title, description, proposed resolution text, outcome, proposer,
  seconder, votes for/against/abstain, resolution number, linked policy id.
- `meeting_attendance`: meeting, user, flat (null for committee meetings), present (bool), RSVP
  (YES | NO | MAYBE), recorded by/at.
- `meeting_minutes`: meeting, body per item (JSON or a child table), status (DRAFT | PENDING |
  PUBLISHED), attachment key/name/type/size, drafted by, approved by/at, published at; `minutes_addendum`
  (minutes, text, by, at).
- Resolution numbers per society, kind and financial year (the finance year setting).
- Permissions as in the table above.

## Pushes

`MEETING_NOTICE` (invitees), `MEETING_REMINDER` (invitees, `meetingReminderHours` before; needs the
first scheduled job in the backend), `MEETING_CANCELLED` / `MEETING_ADJOURNED`, `MINUTES_SUBMITTED`
(approvers), `MINUTES_PUBLISHED` (those who may see them).

## Build steps

| Step | What | Done when |
|---|---|---|
| G1 | V29, meetings and agendas, invitees by kind, notice with notice-period check, RSVP, cancel, adjourn, settings, pushes, scheduled reminder | Tests: notice period and short-notice reason; invitees per kind; committee meetings hidden from residents; adjourn keeps the agenda |
| G2 | Attendance (per flat / per member), quorum from the settings, outcomes and votes per item, resolution numbering, resolutions register with search | Tests: quorum rules (percent, cap, committee); one vote per flat; numbering per year; no outcome before the meeting is held |
| G3 | Minutes: draft, attachment, submit, approve (not the drafter), publish, addenda; visibility by kind; notice-board announcement; policy links | Tests: drafter can't approve; published minutes immutable; committee minutes hidden from residents |
| G4 | Web: meetings list and calendar strip, meeting page (agenda, notice, RSVP, attendance sheet, outcomes, minutes editor and approval), resolutions register, dashboard card "Next meeting" | Unit tests, build |
| G5 | Postman folder, docs | Full Postman run |

Each step ends with the suite passing and its own commit.

## Open questions

1. **Voting:** recorded counts from a show of hands (proposed), or online e-voting in the app (members
   vote before or during the meeting; needs secrecy and one-vote-per-flat rules)?
2. **Who counts for quorum and votes at general meetings:** one per member flat, owner side only
   (proposed), or every owner/adult resident?
3. **Committee meetings:** minutes visible to the committee only (proposed), or to all residents?
4. **Approving minutes:** the chairman approves them online (proposed), or they're confirmed as the
   first item of the next meeting (as many bye-laws say), or both?
5. **Quorum defaults:** two thirds of member flats or 20, whichever is less, for general meetings and
   more than half for the committee (proposed) — or your society's own numbers?
6. **Elections** of the managing committee (nominations, ballots, terms of office): out of scope for
   now (proposed), or part of this module?
7. **Testing:** Postman folder only, as for policies (proposed), or a Playwright walkthrough too?
