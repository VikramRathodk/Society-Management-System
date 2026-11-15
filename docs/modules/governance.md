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

## Decisions (product owner, 2026-11-15)

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
4. **Resolutions decided by online e-voting.** Each agenda item can carry a proposed resolution with a
   proposer and seconder. The secretary **opens voting** on it (before or during the meeting) and
   **closes** it; members vote FOR / AGAINST / ABSTAIN in the app, **one vote per member flat** at
   general meetings (one per committee member at committee meetings). **A vote is final once cast**
   (the app asks to confirm), since changing it would mean keeping the choice somewhere. The ballot is
   **secret**: the app records *that* a flat voted (to stop double voting and to
   show turnout) but stores only the running totals, never which way a flat voted. On close the outcome
   is counted (PASSED when FOR exceeds AGAINST; abstentions don't count) and can't be changed; the
   secretary may instead mark an item DEFERRED without voting. Passed resolutions get a number
   (`AGM/2026-27/3`) and go into a searchable **resolutions register**.
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
| Vote on an open resolution (once per member flat; per member at committee meetings) | owner side of a flat; committee members for committee meetings | `GOVERNANCE.VOTE.CREATE` |
| Open and close voting, defer an item, record attendance, draft minutes | SECRETARY and above | `GOVERNANCE.MINUTES.CREATE` |
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
- `agenda_item`: meeting, position, title, description, proposed resolution text, proposer, seconder,
  voting status (NOT_OPEN | OPEN | CLOSED), opened/closed by and at, tallies for/against/abstain,
  outcome (PASSED | REJECTED | DEFERRED), resolution number, linked policy id.
- `ballot`: item, voter key (the flat for general meetings, the user for committee meetings), voted by,
  voted at — unique per item and voter key; **no choice column** (secret ballot). The ballot row and
  the tally increment are written in one transaction with the item row locked, so a vote counts
  exactly once; a second vote for the same flat is refused (409).
- `meeting_attendance`: meeting, user, flat (null for committee meetings), present (bool), RSVP
  (YES | NO | MAYBE), recorded by/at.
- `meeting_minutes`: meeting, body per item (JSON or a child table), status (DRAFT | PENDING |
  PUBLISHED), attachment key/name/type/size, drafted by, approved by/at, published at; `minutes_addendum`
  (minutes, text, by, at).
- Resolution numbers per society, kind and financial year (the finance year setting).
- Permissions as in the table above.

## Pushes

`MEETING_NOTICE` (invitees), `MEETING_REMINDER` (invitees, `meetingReminderHours` before; needs the
first scheduled job in the backend), `MEETING_CANCELLED` / `MEETING_ADJOURNED`, `VOTING_OPEN` (those
who may vote on the item), `VOTING_CLOSED` (invitees, with the result), `MINUTES_SUBMITTED`
(approvers), `MINUTES_PUBLISHED` (those who may see them).

## Build steps

| Step | What | Done when |
|---|---|---|
| G1 | V29, meetings and agendas, invitees by kind, notice with notice-period check, RSVP, cancel, adjourn, settings, pushes, scheduled reminder | Tests: notice period and short-notice reason; invitees per kind; committee meetings hidden from residents; adjourn keeps the agenda |
| G2 | Attendance (per flat / per member), quorum from the settings; e-voting: open/close per item, secret ballot, one vote per flat (owner side) or committee member, tallies, outcome on close, defer; resolution numbering, resolutions register with search | Tests: quorum rules (percent, cap, committee); one vote per flat even with two owners; tenants can't vote; no choice stored; voting only while open; outcome fixed on close; numbering per year |
| G3 | Minutes: draft, attachment, submit, approve (not the drafter), publish, addenda; visibility by kind; notice-board announcement; policy links | Tests: drafter can't approve; published minutes immutable; committee minutes hidden from residents |
| G4 | Web: meetings list and calendar strip, meeting page (agenda, notice, RSVP, attendance sheet, voting with live turnout and results on close, minutes editor and approval), resolutions register, dashboard cards "Next meeting" and "Vote now" | Unit tests, build |
| G5 | Postman folder, docs | Full Postman run |

Each step ends with the suite passing and its own commit.

## Answered questions (product owner, 2026-11-15)

1. **Voting:** online e-voting, opened and closed by the secretary per resolution, **secret ballot**
   (only totals; who voted is recorded, not how).
2. **Members:** one per member flat, owner side, for quorum and votes at general meetings.
3. **Committee meetings:** minutes for the committee only.
4. **Minutes:** the chairman (or another approver, not the drafter) approves them in the app.
5. **Quorum defaults:** two thirds of member flats or 20, whichever is less; committee more than half
   (settings).
6. **Elections:** later, not in this module.
7. **Testing:** Postman folder only (as for policies).
