# Rules & policies (module 24)

The society's rule book: by-laws, parking, pets, renovation, move-in/move-out, amenity rules and the
like, written by the committee, approved, published to everyone, kept with every past version, and
(where it matters) acknowledged by residents. Planned 2026-11-15 on backend branch `feature/policy`;
build steps P1–P4 below.

## Today

Rules go out as notices. A notice can be edited or deleted with no trace of what it said before,
nobody approves it, it scrolls off the board, and nobody can tell who has read it. Societies keep the
real by-laws as a PDF in someone's mail.

## Proposed decisions (to confirm, see "Open questions")

1. **A policy has versions.** Title and category belong to the policy; the text, an optional
   attachment (the signed PDF of the by-laws, a parking map) and the effective date belong to a
   version. Editing a published policy starts a new draft version; the published one stays in force
   until the new one is published. Every published version is kept and readable, with a change
   summary.
2. **Approval before publishing,** switched by a per-society setting (on by default). Committee and
   above draft and submit; secretary and above approve or send back with a reason. An approver can't
   approve their own draft unless the society allows it (small committees). With approval off, the
   author publishes directly.
3. **Publishing is like posting a notice:** everyone in the society is pushed (`POLICY_PUBLISHED`)
   and the policy appears on a Rules page grouped by category. Archiving takes a policy out of force
   without deleting its history.
4. **Acknowledgement is per policy.** The author ticks "residents must acknowledge"; each resident
   then sees "I have read and accept" on that version. Committee sees, per flat, who has and hasn't,
   can send a reminder push, and export a CSV. A new version asks for fresh acknowledgements only
   when the publisher marks it as a material change (fixing a typo doesn't).
5. **Categories are per society,** seeded with a default list the secretary can rename, add to or
   hide (per [dynamic configuration](../MODULES.md)): By-laws, Parking, Pets, Noise & timings,
   Renovation & repairs, Move-in / move-out, Amenities, Security & visitors, Waste & cleanliness,
   Payments & dues, Other.

## Who may do what (within one society)

| Action | Who | Permission |
|---|---|---|
| Read published policies and their history | everyone with a role in the society | `POLICY.RULE.VIEW` |
| See drafts and pending versions | committee and above | `POLICY.RULE.CREATE` |
| Draft, edit own draft, submit, withdraw | COMMITTEE and above | `POLICY.RULE.CREATE` / `UPDATE` |
| Approve, send back, publish, archive, delete a never-published draft | SECRETARY and above | `POLICY.RULE.APPROVE` |
| Acknowledge | anyone with a flat role | `POLICY.ACKNOWLEDGEMENT.CREATE` |
| See acknowledgements, remind, export | COMMITTEE and above | `POLICY.ACKNOWLEDGEMENT.VIEW` |
| Manage categories | SECRETARY and above | `SOCIETY.SETTINGS.UPDATE` |

## Life of a version

```
DRAFT ──submit──▶ PENDING ──approve──▶ PUBLISHED ──newer version published──▶ SUPERSEDED
  ▲                  │
  └──send back───────┘ (reason; author edits and resubmits)
  └──withdraw────────┘
```

- With approval off, `DRAFT ──publish──▶ PUBLISHED`.
- One open (DRAFT or PENDING) version per policy at a time.
- **Effective date** may be in the future: the version is published (readable, pushed, acknowledged)
  but marked "takes effect on …"; the earlier version stays "in force" until that date.
- A policy is **DRAFT** until its first version is published, then **ACTIVE**, or **ARCHIVED**
  (hidden from the Rules page, visible under "Archived", can be restored).
- A published version is never edited or deleted.

## Settings (new group "Rules & policies" in society settings)

- `policy.approval-required` — default on.
- `policy.self-approval-allowed` — default off; an approver may approve a draft they wrote.
- `policy.acknowledgement-reminder-days` — default 7; earliest repeat reminder to the same flat.

## Data (V26)

- `policy_category`: society, name, position, hidden; defaults inserted for each society (existing
  and new).
- `policy`: society, category, title, status (DRAFT | ACTIVE | ARCHIVED), `requires_acknowledgement`,
  current published version id, created/updated/archived by and at.
- `policy_version`: policy, number (1, 2, …), body (plain text with simple Markdown, up to 50,000
  characters), change summary, effective from, status (DRAFT | PENDING | PUBLISHED | SUPERSEDED),
  `material_change`, attachment key / name / type / size (photo storage, JPEG/PNG/WebP/PDF up to
  5 MB, the same check as finance documents), author, submitted, reviewed by/at, reason, published
  by/at.
- `policy_acknowledgement`: version, user, flat, at; unique per version and user.
- `policy_reminder`: version, flat, sent at (for the reminder limit).
- Permissions: `POLICY.RULE.VIEW/CREATE/UPDATE/APPROVE`, `POLICY.ACKNOWLEDGEMENT.CREATE/VIEW`, given to
  the roles in the table above.
- Module dependencies: `policy` may use `common`, `authorization`, `society`, `user`, `notification`
  (as `notice` does); added to `ModuleDependencyTest`.

## API (planned)

- `GET /api/societies/{id}/policies?category=&status=&q=` — published list (drafts too for authors and
  approvers), with "needs your acknowledgement" per item.
- `POST /api/societies/{id}/policies` — new policy with its first draft.
- `GET /api/policies/{id}` — policy, current version, open draft (if allowed), version list.
- `PATCH /api/policies/{id}` — title, category, `requiresAcknowledgement`.
- `GET /api/policies/{id}/versions/{n}` — one version; `GET …/versions/{n}/attachment`.
- `POST /api/policies/{id}/versions` — start a new draft from the current text.
- `PATCH /api/policy-versions/{id}` — edit a draft; `PUT|DELETE …/attachment`.
- `POST /api/policy-versions/{id}/submit | withdraw | approve | send-back {reason} | publish`.
- `POST /api/policies/{id}/archive | restore`; `DELETE /api/policies/{id}` (never published only).
- `POST /api/policy-versions/{id}/acknowledge`.
- `GET /api/policy-versions/{id}/acknowledgements?state=pending|done` (per flat),
  `POST …/remind` (pending flats, respecting the reminder limit), `GET …/acknowledgements.csv`.
- `GET|POST|PATCH /api/societies/{id}/policy-categories`.
- `GET /api/societies/{id}/policies/pending-acknowledgements` — the caller's own, for the dashboard.

Push events: `POLICY_PUBLISHED` (everyone but the publisher), `POLICY_SUBMITTED` (approvers),
`POLICY_SENT_BACK` and `POLICY_APPROVED` (author), `POLICY_ACKNOWLEDGEMENT_REMINDER` (flat members).
Nothing is pushed to residents while the society is in Setup (no flat roles exist then).

## Build steps

| Step | What | Done when |
|---|---|---|
| P1 | V26, categories with defaults, policies and versions, approval workflow with the settings, publish/supersede/effective date, archive, attachment, pushes | Tests: one open version per policy; approval on/off; self-approval refused unless allowed; published versions immutable; future effective date keeps the old version in force |
| P2 | Acknowledgements: acknowledge, per-flat report, remind with the limit, CSV, material change resets, caller's pending list | Tests: duplicate acknowledge harmless; minor version keeps acknowledgements; reminder limit; only flat roles acknowledge |
| P3 | Web: Rules page by category with search, policy page with version history and diff of text, editor with attachment, approvals queue, acknowledge banner + dashboard tile, acknowledgement report, categories and settings | Unit tests, build |
| P4 | Postman folder, Playwright e2e (draft → approve → publish → acknowledge → new material version), docs | Full Postman run; e2e green |

Each step ends with the suite passing and its own commit.

## Open questions

1. **Approval:** is one approver (secretary or above) enough, or should some policies (by-laws) need
   more than one, e.g. secretary *and* chairman, or a committee vote? Proposed: one approver now;
   multi-approver later with Meeting & Governance (module 26).
2. **Acknowledgement:** per person (every adult in the flat) or once per flat? Proposed: per person,
   and the report counts a flat as done when its owner (or tenant, for rented flats) has acknowledged.
3. **Audience:** should some policies be visible only to the committee or only to staff (guard
   instructions)? Proposed: everyone in the society for now.
4. **Notice board:** should publishing also post a notice ("Parking rules updated")? Proposed: the
   push and a "New / updated" badge on the Rules page are enough; no notice.
5. **Fines for breaking a rule** (link a rule to a maintenance charge head)? Proposed: out of scope
   for this module; revisit with violations / incidents (module 21).
