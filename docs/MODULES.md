# Backend Modules — Reference

The full target module list for the Society Management System backend, and where each one stands in
the code today. The backend is a modular monolith: one top-level package per module under
`org.devvikram.societymanagement`, each with `controller/ dto/ service/ model/ repository/`, and the
allowed dependencies between modules enforced by `ModuleDependencyTest` (see "Module Structure" in
`society_planning.md`).

A package is created only when its module's first feature is built, and is then added to
`ModuleDependencyTest`. Package names in *italics* are proposed, not created yet.

Each built module has its own doc in [docs/modules/](modules/) (rules, endpoints, permissions, tables):
[auth](modules/auth.md) · [authorization](modules/authorization.md) · [user](modules/user.md) ·
[society](modules/society.md) · [visitor](modules/visitor.md) · [notice](modules/notice.md) ·
[complaint](modules/complaint.md) · [maintenance](modules/maintenance.md) · [finance](modules/finance.md) · [policy](modules/policy.md) · [notification](modules/notification.md).

**Status:** ✅ built · 🟡 partly built / folded into another module · ⏳ planned (blocked or deferred) · — not started

| # | Module | Package | Status | Notes |
|---|---|---|---|---|
| 1 | Common | `common` | ✅ | Cross-cutting only: config, exceptions, photo storage, CSV export. No business logic. |
| 2 | Auth & Authentication | `auth` | ✅ | Login, JWT, Spring Security wiring. |
| 3 | User Management | `user` | ✅ | User identity, profile, change own password, admin password reset, activate/deactivate/delete, FCM token. Self-service forgot-password not built (needs OTP). |
| 4 | Authorization & Roles | `authorization` | ✅ | Dynamic roles, hierarchical permissions, `Access`, `@RequiresPermission`. |
| 5 | Society Management | `society` | ✅ | Complex and society CRUD; per-society settings (V16). |
| 6 | Society Structure | `society` | ✅ | Wings and flats; flat directory scoping. Shares the package with 5 for now; split only if it grows. |
| 7 | Society Onboarding | `onboarding` | ✅ | V23–V25: Setup → Live (flat roles wait for go-live), society profile and logo, setup checklist; platform-led creation with the secretary's invitation; self-signup registrations with super-admin approval and a per-address limit; wings and flats from a pattern; residents from a CSV staged and sent at go-live (links CSV + QR cards on the web). Web wizard, registration page, registrations list; Postman folder 19; Playwright e2e for both paths. See [onboarding](modules/onboarding.md). |
| 8 | Invitation Management | `invitation` | ✅ | V17–V18: invite by rank or (residents) onto their own flat with committee approval, link + QR, accept as new or existing user, revoke, resend/renew, decline, expiry, per-flat limit, pushes; web screens. See [invitation](modules/invitation.md). |
| 9 | Resident & Membership | `user` (`UserRole`) | 🟡 | A role assignment with a flat *is* the residency today; `resident_type` on the user. Move to *`resident`* when membership gets its own workflow (move-in/out, tenancy). |
| 10 | Visitor Management | `visitor` | ✅ | M3. Gate flow, approvals, override, photo, CSV export, push. |
| 11 | Staff Management | *`staff`* | 🟡 | `StaffMember` entity sits in `society` as schema only; workflow is Phase 2. Move it out when built. |
| 12 | Vendor Management | *`vendor`* | — | |
| 13 | Complaint Management | `complaint` | ✅ | M5. Flat-owned tickets, status workflow, activity timeline, CSV export, push. |
| 14 | Maintenance Management | `maintenance` | ✅ | M7a–M7c: settings and charge heads, billing cycles, bills with frozen lines, waive, offline payments with receipts and void, late fees, opening balances, dues/defaulters reports and reminders, CSV exports, push; advance payments kept as credit and refunds of credit (V21). Web screens (dues, bills, payments and receipts, cycles, opening balances, settings) and a Postman folder. See [maintenance.md](modules/maintenance.md). |
| 15 | Finance & Accounting | `finance` | ✅ | All 6 steps done, backend and web: chart of accounts with groups, bank accounts, openings, ledgers, audit log (V19); receipts, payments, transfers, journals, approval, void by reversal, lock date, documents, day book, cash & bank summary (V20); maintenance credit and refunds (V21); maintenance posts to the books automatically, maintenance check; trial balance, I&E, R&P, balance sheet, resident summary, CSV. Bank reconciliation (V22, [finance-reconciliation](modules/finance-reconciliation.md)). V1.1 upgrades on hold. See [finance](modules/finance.md). |
| 16 | Payment Management | *`payment`* | — | Phase 2. |
| 17 | Parking Management | *`parking`* | 🟡 | `ParkingSlot` entity sits in `society` as schema only; workflow is Phase 2. Move it out when built. |
| 18 | Facility & Amenities | *`facility`* | — | Phase 2 (amenity/clubhouse booking). |
| 19 | Asset Management | *`asset`* | — | |
| 20 | Work Order Management | *`workorder`* | — | Pairs with `COMPLAINT.RESOLUTION.ASSIGN` once staff/vendors exist. |
| 21 | Incident & Emergency Management | *`incident`* | — | |
| 22 | Notice & Announcement | `notice` | ✅ | M4. Notice board, own-post rule, moderation, push. (Was the `communication` package until 2026-10-30.) |
| 23 | Communication | — | — | In-app chat/messaging is out of MVP scope. |
| 24 | Rules & Policies | `policy` | ✅ | Built 2026-11-15 on `feature/policy` (V27): categories, versioned policies with approval by the chairman and above, publish with push, effective dates, archive, attachment. P2 acknowledgements, per-flat report, reminders, CSV; P3 web screens; P4 Postman folder 20. See [policy](modules/policy.md). |
| 25 | Document Management | *`document`* | — | Could build on `common/storage/PhotoStorage`. |
| 26 | Meeting & Governance | `governance` | 🟡 | G1 built 2026-11-15 (V29: meetings, agendas, notices, RSVP, cancel/adjourn, reminders — the first scheduled job). Planned on `feature/governance`: AGM/SGM/committee meetings, notices with notice periods, attendance and quorum, resolutions by secret online e-voting (one vote per member flat) with a register, minutes approved by the chairman. Decisions confirmed. See [governance](modules/governance.md) (steps G1–G5). |
| 27 | Notification Management | `notification` | 🟡 | Delivery only (`PushNotifier`, FCM). Each module decides who to notify. Preferences, in-app inbox and history not built. |
| 28 | Reporting & Analytics | *`reporting`* | 🟡 | CSV exports live in `visitor` / `complaint` (shared `common/export/Csv`). Dashboard (`DASHBOARD.OVERVIEW.VIEW`) is Phase 2. |
| 29 | Audit & Activity Logs | *`audit`* | 🟡 | Only module-local history so far (complaint timeline, `updated_by`, visitor override flag). Global audit log (`AUDIT_LOG.VIEW`) is Phase 2. |
| 30 | Subscription & Billing | *`subscription`* | — | Platform-level billing of societies. |
| 31 | Platform Administration | `society`, `authorization` | 🟡 | SUPER_ADMIN endpoints (complex/society create and delete, roles) live in their owning modules. |
| 32 | Integration Management | *`integration`* | — | Firebase is configured in `notification`; a module only if integrations become configurable per society
## Communication (23) vs Notice & Announcement (22)

They are separate modules. Notices live in `notice`; the name `communication` is kept free for
in-app chat and messaging, which is out of MVP scope.
