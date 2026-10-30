# Backend Modules — Reference

The full target module list for the Society Management System backend, and where each one stands in
the code today. The backend is a modular monolith: one top-level package per module under
`org.devvikram.societymanagement`, each with `controller/ dto/ service/ model/ repository/`, and the
allowed dependencies between modules enforced by `ModuleDependencyTest` (see "Module Structure" in
`society_planning.md`).

A package is created only when its module's first feature is built, and is then added to
`ModuleDependencyTest`. Package names in *italics* are proposed, not created yet.

**Status:** ✅ built · 🟡 partly built / folded into another module · ⏳ planned (blocked or deferred) · — not started

| # | Module | Package | Status | Notes |
|---|---|---|---|---|
| 1 | Common | `common` | ✅ | Cross-cutting only: config, exceptions, photo storage, CSV export. No business logic. |
| 2 | Auth & Authentication | `auth` | ✅ | Login, JWT, Spring Security wiring. |
| 3 | User Management | `user` | ✅ | User identity, profile, change own password, admin password reset, activate/deactivate/delete, FCM token. Self-service forgot-password not built (needs OTP). |
| 4 | Authorization & Roles | `authorization` | ✅ | Dynamic roles, hierarchical permissions, `Access`, `@RequiresPermission`. |
| 5 | Society Management | `society` | ✅ | Complex and society CRUD + settings. |
| 6 | Society Structure | `society` | ✅ | Wings and flats; flat directory scoping. Shares the package with 5 for now; split only if it grows. |
| 7 | Society Onboarding | *`onboarding`* | ⏳ | Blocked on the onboarding-model decision (see Open Decisions in the plan). |
| 8 | Invitation Management | *`invitation`* | ⏳ | Blocked on link channel, identity proof and phone format decisions. |
| 9 | Resident & Membership | `user` (`UserRole`) | 🟡 | A role assignment with a flat *is* the residency today; `resident_type` on the user. Move to *`resident`* when membership gets its own workflow (move-in/out, tenancy). |
| 10 | Visitor Management | `visitor` | ✅ | M3. Gate flow, approvals, override, photo, CSV export, push. |
| 11 | Staff Management | *`staff`* | 🟡 | `StaffMember` entity sits in `society` as schema only; workflow is Phase 2. Move it out when built. |
| 12 | Vendor Management | *`vendor`* | — | |
| 13 | Complaint Management | `complaint` | ✅ | M5. Flat-owned tickets, status workflow, activity timeline, CSV export, push. |
| 14 | Maintenance Management | *`maintenance`* | — | Phase 2 (maintenance billing). |
| 15 | Finance & Accounting | *`finance`* | — | Phase 2 (income/expense ledger). |
| 16 | Payment Management | *`payment`* | — | Phase 2. |
| 17 | Parking Management | *`parking`* | 🟡 | `ParkingSlot` entity sits in `society` as schema only; workflow is Phase 2. Move it out when built. |
| 18 | Facility & Amenities | *`facility`* | — | Phase 2 (amenity/clubhouse booking). |
| 19 | Asset Management | *`asset`* | — | |
| 20 | Work Order Management | *`workorder`* | — | Pairs with `COMPLAINT.RESOLUTION.ASSIGN` once staff/vendors exist. |
| 21 | Incident & Emergency Management | *`incident`* | — | |
| 22 | Notice & Announcement | `notice` | ✅ | M4. Notice board, own-post rule, moderation, push. (Was the `communication` package until 2026-10-30.) |
| 23 | Communication | — | — | In-app chat/messaging is out of MVP scope. |
| 24 | Rules & Policies | *`policy`* | ⏳ | Deferred until after the MVP; reuses notice publishing, approval workflow undefined. |
| 25 | Document Management | *`document`* | — | Could build on `common/storage/PhotoStorage`. |
| 26 | Meeting & Governance | *`governance`* | — | |
| 27 | Notification Management | `notification` | 🟡 | Delivery only (`PushNotifier`, FCM). Each module decides who to notify. Preferences, in-app inbox and history not built. |
| 28 | Reporting & Analytics | *`reporting`* | 🟡 | CSV exports live in `visitor` / `complaint` (shared `common/export/Csv`). Dashboard (`DASHBOARD.OVERVIEW.VIEW`) is Phase 2. |
| 29 | Audit & Activity Logs | *`audit`* | 🟡 | Only module-local history so far (complaint timeline, `updated_by`, visitor override flag). Global audit log (`AUDIT_LOG.VIEW`) is Phase 2. |
| 30 | Subscription & Billing | *`subscription`* | — | Platform-level billing of societies. |
| 31 | Platform Administration | `society`, `authorization` | 🟡 | SUPER_ADMIN endpoints (complex/society create and delete, roles) live in their owning modules. |
| 32 | Integration Management | *`integration`* | — | Firebase is configured in `notification`; a module only if integrations become configurable per society
## Communication (23) vs Notice & Announcement (22)

They are separate modules. Notices live in `notice`; the name `communication` is kept free for
in-app chat and messaging, which is out of MVP scope.
