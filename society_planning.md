# Society Management System — Development Plan (MVP)

## Overview
A mobile-first Society Management System serving four roles — **Resident, Security Guard, Committee Member, Admin** — built as a single Flutter app (role-based UI) backed by a Spring Boot + PostgreSQL API.

**Project type:** Freelance/client project
**Team:** Solo developer
**Approach:** MVP first, phased rollout, client-demoable at each milestone

---

## Tech Stack

| Layer | Choice |
|---|---|
| Mobile | Flutter (BLoC, Clean Architecture, go_router, get_it, Dio) |
| Backend | Spring Boot 4 (Kotlin, Gradle Kotlin DSL), Spring Security + JWT |
| Database | PostgreSQL |
| Push Notifications | Firebase Cloud Messaging (FCM) |
| Auth | JWT-based, role embedded in claims, no self-signup in MVP |
| Migrations | Flyway (schema is version-controlled, not Hibernate auto-DDL) |
| Cache | Caffeine (backs the role→permission lookup) |

Backend package root: `org.devvikram.societymanagement`

---

## MVP Scope

### Feature List by Role

**Resident**
- Login (admin-provisioned account, no self-signup)
- View own flat/profile details
- View visitor requests → Approve / Reject (push notification)
- View society notices
- Raise a complaint + track status / view complaint history

**Security Guard**
- Login
- Log a new visitor (name, phone, purpose, optional photo)
- View status of visitors they logged
- Mark visitor as Entered / Exited at gate

**Committee Member**
- Login
- View flat/resident directory
- Post notices
- View all visitor logs (read-only oversight)
- View all complaints + update status (Open → In Progress → Resolved)

**Admin**
- Login
- Add/manage residents, guards, committee members
- Manage society structure (wings, flats)
- Post notices
- View all visitor logs
- View + update all complaints

### Feature List by Module

| Module | What it does | Who touches it |
|---|---|---|
| Auth | Login, JWT session, permission-based routing | All roles |
| User & Flat Directory | Society → Wing → Flat → Resident mapping | Admin creates; all view relevant parts |
| Visitor Management | Guard logs entry → Resident approves/rejects → push notification → gate exit tracking | Guard, Resident, Committee (view), Admin (view) |
| Notices | Post and view society-wide announcements | Committee/Admin post; all view |
| Complaints | Resident raises issue → Committee/Admin manage status | Resident raises; Committee/Admin manage |

### Explicitly Out of MVP Scope
- Self-registration/signup
- Maintenance billing & payments
- Amenity/clubhouse booking
- Web admin panel (Admin uses mobile app in MVP)
- In-app chat/messaging

---

## Phase 2 Backlog (Post-MVP)
- Maintenance billing & payments
- Amenity/clubhouse booking
- Expense tracking (society income/expense ledger)
- Parking allotment (per-flat slots, visitor parking) — schema already in place, workflow not built
- Staff/maid management (registered domestic help, guard verification at gate) — schema already in place, workflow not built
- Self-registration
- Web admin panel
- Dashboard/summary overview (`DASHBOARD.OVERVIEW.VIEW`) — pending approvals, open complaints count, etc.
- Audit log for permission changes (`AUDIT_LOG.VIEW`) — becomes valuable once `SYSTEM.PERMISSION.UPDATE` is actively used
- `COMPLAINT.RESOLUTION.ASSIGN` activation — assign complaints to staff/vendor once Staff Management ships
- What exactly is shared at the Complex (multi-society) level — amenities, notices, a common visitor log — to be confirmed with client

---

## Resolved Design Decisions

- **Multiple residents per flat:** Yes — distinguished by `resident_type` (OWNER/FAMILY/TENANT) on `app_user`. Visitor approval notifies all active residents of the flat; first response wins via an atomic conditional update (`UPDATE ... WHERE id=? AND status='PENDING'`), second responder sees "Already responded by [name]".
- **Parking & Staff Management schema:** Tables added now (`parking_slot`, `staff_member`), alongside reserved/unassigned permission codes. No business logic/workflow built yet — deferred to Phase 2.
- **Multi-society support:** `Complex` entity added (optional, nullable) above `Society`, supports both society-scoped and complex-scoped guards (shared gate). What specifically gets shared at the complex level is still open.

---

## Database Schema (PostgreSQL)

```sql
-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Complex (optional top level — a township containing multiple societies)
CREATE TABLE complex (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    address TEXT,
    created_at TIMESTAMP DEFAULT now()
);

-- Society (always exists — independently managed, even inside a Complex)
CREATE TABLE society (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    complex_id UUID REFERENCES complex(id), -- nullable: standalone society if null
    name VARCHAR(255) NOT NULL,
    address TEXT,
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE wing (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    society_id UUID REFERENCES society(id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL -- e.g. "A", "B"
);

CREATE TABLE flat (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wing_id UUID REFERENCES wing(id) ON DELETE CASCADE,
    flat_number VARCHAR(20) NOT NULL,
    UNIQUE(wing_id, flat_number)
);

-- Users & roles
CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    society_id UUID REFERENCES society(id),
    complex_id UUID REFERENCES complex(id),
    name VARCHAR(255) NOT NULL,
    phone VARCHAR(15) UNIQUE NOT NULL,
    email VARCHAR(255),
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('RESIDENT','GUARD','COMMITTEE','ADMIN')),
    flat_id UUID REFERENCES flat(id), -- null for GUARD/ADMIN; multiple app_user rows can share one flat_id
    resident_type VARCHAR(20) CHECK (resident_type IN ('OWNER','FAMILY','TENANT')), -- only set when role = RESIDENT
    fcm_token VARCHAR(255),
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT now(),
    CONSTRAINT chk_user_scope CHECK (
        (society_id IS NOT NULL AND complex_id IS NULL)
        OR (complex_id IS NOT NULL AND society_id IS NULL)
    )
);

-- Visitor management
CREATE TABLE visitor_entry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    society_id UUID REFERENCES society(id),
    flat_id UUID REFERENCES flat(id),
    visitor_name VARCHAR(255) NOT NULL,
    visitor_phone VARCHAR(15),
    purpose VARCHAR(100), -- delivery, guest, cab, service, etc.
    photo_url TEXT,
    logged_by UUID REFERENCES app_user(id), -- guard
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','ENTERED','EXITED')),
    responded_by UUID REFERENCES app_user(id), -- which resident acted (flat may have multiple residents)
    entry_time TIMESTAMP DEFAULT now(),
    exit_time TIMESTAMP,
    responded_at TIMESTAMP
);

-- Notices
CREATE TABLE notice (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    society_id UUID REFERENCES society(id),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    posted_by UUID REFERENCES app_user(id),
    created_at TIMESTAMP DEFAULT now()
);

-- Complaints
CREATE TABLE complaint (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    society_id UUID REFERENCES society(id),
    raised_by UUID REFERENCES app_user(id),
    category VARCHAR(50), -- plumbing, electrical, security, other
    description TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
    updated_by UUID REFERENCES app_user(id),
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now()
);

-- Parking (schema now, workflow deferred to Phase 2)
CREATE TABLE parking_slot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    society_id UUID REFERENCES society(id),
    slot_number VARCHAR(20) NOT NULL,
    flat_id UUID REFERENCES flat(id), -- nullable: unassigned slot
    vehicle_type VARCHAR(20), -- car, bike, etc.
    created_at TIMESTAMP DEFAULT now(),
    UNIQUE(society_id, slot_number)
);

-- Staff / domestic help (schema now, workflow deferred to Phase 2)
CREATE TABLE staff_member (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    society_id UUID REFERENCES society(id),
    flat_id UUID REFERENCES flat(id), -- nullable: society-wide staff (e.g. sweeper) vs flat-specific (maid)
    name VARCHAR(255) NOT NULL,
    phone VARCHAR(15),
    photo_url TEXT,
    staff_type VARCHAR(50), -- maid, cook, driver, cleaner, etc.
    id_proof_url TEXT,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT now()
);

-- Permission system (DB-backed, hierarchical codes)
CREATE TABLE permission (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module VARCHAR(30) NOT NULL,
    feature VARCHAR(30) NOT NULL,
    subfeature VARCHAR(30),
    action VARCHAR(20) NOT NULL,
    code VARCHAR(100) UNIQUE NOT NULL,
    description VARCHAR(255),
    UNIQUE(module, feature, subfeature, action)
);

CREATE TABLE role_permission (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role VARCHAR(20) NOT NULL CHECK (role IN ('RESIDENT','GUARD','COMMITTEE','ADMIN')),
    permission_id UUID REFERENCES permission(id) ON DELETE CASCADE,
    UNIQUE(role, permission_id)
);

-- Indexes
CREATE INDEX idx_app_user_phone ON app_user(phone);
CREATE INDEX idx_visitor_entry_flat_status ON visitor_entry(flat_id, status);
CREATE INDEX idx_complaint_society_status ON complaint(society_id, status);
```

**Multi-resident visitor approval:** push notification goes to every active resident of the flat (all their `fcm_token`s). Whichever resident responds first wins — the backend does an atomic conditional update, so a second resident's response is silently rejected if the request is already resolved. Flutter shows "Already responded by [name]" if the resident opens a request that's no longer pending.

**Guard scope check (Complex vs Society):**
```kotlin
fun canGuardAccessFlat(guard: AppUser, flat: Flat): Boolean {
    return when {
        guard.societyId != null -> flat.wing.society.id == guard.societyId
        guard.complexId != null -> flat.wing.society.complexId == guard.complexId
        else -> false
    }
}
```
`VISITOR.ENTRY.VIEW` stays the same permission code either way — this check just decides which flats a given guard's view is scoped to.

---

## Permission System (DB-backed, hierarchical codes)

Permission codes follow **`MODULE.FEATURE[.SUBFEATURE].ACTION`** — hierarchical, not flat.

### Standard Action Set

| Action | Meaning |
|---|---|
| `CREATE` | Add a new record |
| `VIEW` | Read/list |
| `UPDATE` | Edit an existing record |
| `DELETE` | Remove a record |
| `APPROVE` | Approve a pending request |
| `REJECT` | Reject a pending request |
| `ASSIGN` | Assign ownership/responsibility |
| `EXPORT` | Export data (reports, logs) |

### Full Permission Catalog (by category)

**USER**
```
USER.PROFILE.VIEW              View own/other profile
USER.PROFILE.UPDATE            Edit own profile
USER.ACCOUNT.CREATE            Create a new user account
USER.ACCOUNT.VIEW              View all user accounts
USER.ACCOUNT.UPDATE            Edit another user account
USER.ACCOUNT.DELETE            Deactivate/remove a user
```

**FLAT**
```
FLAT.DIRECTORY.VIEW            View flat/resident directory
FLAT.STRUCTURE.CREATE          Add wing/flat
FLAT.STRUCTURE.UPDATE          Edit wing/flat
FLAT.STRUCTURE.DELETE          Remove wing/flat
```

**VISITOR**
```
VISITOR.ENTRY.CREATE           Log a visitor entry
VISITOR.ENTRY.VIEW             View visitor logs
VISITOR.ENTRY.UPDATE           Edit / mark entered-exited
VISITOR.ENTRY.DELETE           Remove a visitor log
VISITOR.ENTRY.EXPORT           Export visitor log report
VISITOR.APPROVAL.APPROVE       Approve visitor request
VISITOR.APPROVAL.REJECT        Reject visitor request
```

**NOTICE**
```
NOTICE.POST.CREATE             Post a notice
NOTICE.POST.VIEW               View notices
NOTICE.POST.UPDATE             Edit a notice
NOTICE.POST.DELETE             Delete a notice
```

**COMPLAINT**
```
COMPLAINT.TICKET.CREATE        Raise a complaint
COMPLAINT.TICKET.VIEW          View complaints
COMPLAINT.TICKET.UPDATE        Edit a complaint
COMPLAINT.TICKET.DELETE        Withdraw a complaint
COMPLAINT.TICKET.EXPORT        Export complaint report
COMPLAINT.RESOLUTION.ASSIGN    Assign complaint to staff (seeded, unassigned — Phase 2 hook)
COMPLAINT.RESOLUTION.APPROVE   Mark complaint resolved
COMPLAINT.RESOLUTION.REJECT    Reopen/reject resolution
```

**SOCIETY**
```
SOCIETY.SETTINGS.VIEW          View society profile
SOCIETY.SETTINGS.UPDATE        Edit society profile
```

**COMPLEX** (reserved — no role assigned until a Complex Admin role is confirmed)
```
COMPLEX.SETTINGS.VIEW          View complex profile
COMPLEX.SETTINGS.UPDATE        Edit complex profile
```

**PARKING** (reserved — workflow deferred to Phase 2)
```
PARKING.SLOT.CREATE            Add a parking slot
PARKING.SLOT.VIEW              View parking slots
PARKING.SLOT.UPDATE            Edit a parking slot
PARKING.SLOT.DELETE            Remove a parking slot
PARKING.SLOT.ASSIGN            Assign a slot to a flat
```

**STAFF** (reserved — workflow deferred to Phase 2)
```
STAFF.MEMBER.CREATE            Register a staff member
STAFF.MEMBER.VIEW              View staff members
STAFF.MEMBER.UPDATE            Edit staff member details
STAFF.MEMBER.DELETE            Remove a staff member
```

**SYSTEM** (meta-permission — gates who can edit the permission system itself; must-have, not optional)
```
SYSTEM.PERMISSION.VIEW         View role-permission mappings
SYSTEM.PERMISSION.UPDATE       Edit role-permission mappings
```

*Deferred to Phase 2 (not yet seeded):* `DASHBOARD.OVERVIEW.VIEW`, `AUDIT_LOG.VIEW`.

### Role → Permission Matrix

| Permission Code | Resident | Guard | Committee | Admin |
|---|---|---|---|---|
| `USER.PROFILE.VIEW` | own | — | ✅ | ✅ |
| `USER.PROFILE.UPDATE` | own | — | — | ✅ |
| `USER.ACCOUNT.CREATE` | — | — | — | ✅ |
| `USER.ACCOUNT.VIEW` | — | — | ✅ | ✅ |
| `USER.ACCOUNT.UPDATE` | — | — | — | ✅ |
| `USER.ACCOUNT.DELETE` | — | — | — | ✅ |
| `FLAT.DIRECTORY.VIEW` | own | — | ✅ | ✅ |
| `FLAT.STRUCTURE.CREATE` | — | — | — | ✅ |
| `FLAT.STRUCTURE.UPDATE` | — | — | — | ✅ |
| `FLAT.STRUCTURE.DELETE` | — | — | — | ✅ |
| `VISITOR.ENTRY.CREATE` | — | ✅ | — | — |
| `VISITOR.ENTRY.VIEW` | own flat's | logged by them | ✅ | ✅ |
| `VISITOR.ENTRY.UPDATE` | — | ✅ | — | — |
| `VISITOR.ENTRY.DELETE` | — | — | — | ✅ |
| `VISITOR.ENTRY.EXPORT` | — | — | ✅ | ✅ |
| `VISITOR.APPROVAL.APPROVE` | own flat's | — | — | — |
| `VISITOR.APPROVAL.REJECT` | own flat's | — | — | — |
| `NOTICE.POST.CREATE` | — | — | ✅ | ✅ |
| `NOTICE.POST.VIEW` | ✅ | ✅ | ✅ | ✅ |
| `NOTICE.POST.UPDATE` | — | — | own posts | ✅ |
| `NOTICE.POST.DELETE` | — | — | own posts | ✅ |
| `COMPLAINT.TICKET.CREATE` | ✅ | — | — | — |
| `COMPLAINT.TICKET.VIEW` | own | — | ✅ | ✅ |
| `COMPLAINT.TICKET.UPDATE` | own (before resolved) | — | — | — |
| `COMPLAINT.TICKET.DELETE` | own (before resolved) | — | — | — |
| `COMPLAINT.TICKET.EXPORT` | — | — | ✅ | ✅ |
| `COMPLAINT.RESOLUTION.ASSIGN` | — | — | — | reserved, unassigned |
| `COMPLAINT.RESOLUTION.APPROVE` | — | — | ✅ | ✅ |
| `COMPLAINT.RESOLUTION.REJECT` | — | — | ✅ | ✅ |
| `SOCIETY.SETTINGS.VIEW` | — | — | — | ✅ |
| `SOCIETY.SETTINGS.UPDATE` | — | — | — | ✅ |
| `COMPLEX.SETTINGS.VIEW` | — | — | — | reserved, unassigned |
| `COMPLEX.SETTINGS.UPDATE` | — | — | — | reserved, unassigned |
| `PARKING.SLOT.*` | — | — | — | reserved, unassigned |
| `STAFF.MEMBER.*` | — | — | — | reserved, unassigned |
| `SYSTEM.PERMISSION.VIEW` | — | — | — | ✅ |
| `SYSTEM.PERMISSION.UPDATE` | — | — | — | ✅ |

Note: entries marked "own"/"own flat's"/"own posts" are **row-level ownership checks**, not something the permission code itself can express — the code gates "can this role hit this endpoint at all," ownership is a separate check in the service layer.

### Seed SQL

```sql
-- Permission catalog
INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
-- USER
('USER','PROFILE',NULL,'VIEW','USER.PROFILE.VIEW','View own/other profile'),
('USER','PROFILE',NULL,'UPDATE','USER.PROFILE.UPDATE','Edit own profile'),
('USER','ACCOUNT',NULL,'CREATE','USER.ACCOUNT.CREATE','Create a new user account'),
('USER','ACCOUNT',NULL,'VIEW','USER.ACCOUNT.VIEW','View all user accounts'),
('USER','ACCOUNT',NULL,'UPDATE','USER.ACCOUNT.UPDATE','Edit another user account'),
('USER','ACCOUNT',NULL,'DELETE','USER.ACCOUNT.DELETE','Deactivate/remove a user'),
-- FLAT
('FLAT','DIRECTORY',NULL,'VIEW','FLAT.DIRECTORY.VIEW','View flat/resident directory'),
('FLAT','STRUCTURE',NULL,'CREATE','FLAT.STRUCTURE.CREATE','Add wing/flat'),
('FLAT','STRUCTURE',NULL,'UPDATE','FLAT.STRUCTURE.UPDATE','Edit wing/flat'),
('FLAT','STRUCTURE',NULL,'DELETE','FLAT.STRUCTURE.DELETE','Remove wing/flat'),
-- VISITOR
('VISITOR','ENTRY',NULL,'CREATE','VISITOR.ENTRY.CREATE','Log a visitor entry'),
('VISITOR','ENTRY',NULL,'VIEW','VISITOR.ENTRY.VIEW','View visitor logs'),
('VISITOR','ENTRY',NULL,'UPDATE','VISITOR.ENTRY.UPDATE','Edit/mark entered-exited'),
('VISITOR','ENTRY',NULL,'DELETE','VISITOR.ENTRY.DELETE','Remove a visitor log'),
('VISITOR','ENTRY',NULL,'EXPORT','VISITOR.ENTRY.EXPORT','Export visitor log report'),
('VISITOR','APPROVAL',NULL,'APPROVE','VISITOR.APPROVAL.APPROVE','Approve visitor request'),
('VISITOR','APPROVAL',NULL,'REJECT','VISITOR.APPROVAL.REJECT','Reject visitor request'),
-- NOTICE
('NOTICE','POST',NULL,'CREATE','NOTICE.POST.CREATE','Post a notice'),
('NOTICE','POST',NULL,'VIEW','NOTICE.POST.VIEW','View notices'),
('NOTICE','POST',NULL,'UPDATE','NOTICE.POST.UPDATE','Edit a notice'),
('NOTICE','POST',NULL,'DELETE','NOTICE.POST.DELETE','Delete a notice'),
-- COMPLAINT
('COMPLAINT','TICKET',NULL,'CREATE','COMPLAINT.TICKET.CREATE','Raise a complaint'),
('COMPLAINT','TICKET',NULL,'VIEW','COMPLAINT.TICKET.VIEW','View complaints'),
('COMPLAINT','TICKET',NULL,'UPDATE','COMPLAINT.TICKET.UPDATE','Edit a complaint'),
('COMPLAINT','TICKET',NULL,'DELETE','COMPLAINT.TICKET.DELETE','Withdraw a complaint'),
('COMPLAINT','TICKET',NULL,'EXPORT','COMPLAINT.TICKET.EXPORT','Export complaint report'),
('COMPLAINT','RESOLUTION',NULL,'ASSIGN','COMPLAINT.RESOLUTION.ASSIGN','Assign complaint to staff'),
('COMPLAINT','RESOLUTION',NULL,'APPROVE','COMPLAINT.RESOLUTION.APPROVE','Mark complaint resolved'),
('COMPLAINT','RESOLUTION',NULL,'REJECT','COMPLAINT.RESOLUTION.REJECT','Reopen/reject resolution'),
-- SOCIETY
('SOCIETY','SETTINGS',NULL,'VIEW','SOCIETY.SETTINGS.VIEW','View society profile'),
('SOCIETY','SETTINGS',NULL,'UPDATE','SOCIETY.SETTINGS.UPDATE','Edit society profile'),
-- COMPLEX (reserved)
('COMPLEX','SETTINGS',NULL,'VIEW','COMPLEX.SETTINGS.VIEW','View complex profile'),
('COMPLEX','SETTINGS',NULL,'UPDATE','COMPLEX.SETTINGS.UPDATE','Edit complex profile'),
-- PARKING (reserved)
('PARKING','SLOT',NULL,'CREATE','PARKING.SLOT.CREATE','Add a parking slot'),
('PARKING','SLOT',NULL,'VIEW','PARKING.SLOT.VIEW','View parking slots'),
('PARKING','SLOT',NULL,'UPDATE','PARKING.SLOT.UPDATE','Edit a parking slot'),
('PARKING','SLOT',NULL,'DELETE','PARKING.SLOT.DELETE','Remove a parking slot'),
('PARKING','SLOT',NULL,'ASSIGN','PARKING.SLOT.ASSIGN','Assign a slot to a flat'),
-- STAFF (reserved)
('STAFF','MEMBER',NULL,'CREATE','STAFF.MEMBER.CREATE','Register a staff member'),
('STAFF','MEMBER',NULL,'VIEW','STAFF.MEMBER.VIEW','View staff members'),
('STAFF','MEMBER',NULL,'UPDATE','STAFF.MEMBER.UPDATE','Edit staff member details'),
('STAFF','MEMBER',NULL,'DELETE','STAFF.MEMBER.DELETE','Remove a staff member'),
-- SYSTEM (meta-permission)
('SYSTEM','PERMISSION',NULL,'VIEW','SYSTEM.PERMISSION.VIEW','View role-permission mappings'),
('SYSTEM','PERMISSION',NULL,'UPDATE','SYSTEM.PERMISSION.UPDATE','Edit role-permission mappings');

-- Role -> permission mappings
-- COMPLEX.*, PARKING.*, STAFF.*, COMPLAINT.RESOLUTION.ASSIGN are seeded but intentionally left unmapped.

INSERT INTO role_permission (role, permission_id)
SELECT 'RESIDENT', id FROM permission WHERE code IN (
    'USER.PROFILE.VIEW','USER.PROFILE.UPDATE',
    'FLAT.DIRECTORY.VIEW',
    'VISITOR.ENTRY.VIEW','VISITOR.APPROVAL.APPROVE','VISITOR.APPROVAL.REJECT',
    'NOTICE.POST.VIEW',
    'COMPLAINT.TICKET.CREATE','COMPLAINT.TICKET.VIEW','COMPLAINT.TICKET.UPDATE','COMPLAINT.TICKET.DELETE'
);

INSERT INTO role_permission (role, permission_id)
SELECT 'GUARD', id FROM permission WHERE code IN (
    'VISITOR.ENTRY.CREATE','VISITOR.ENTRY.VIEW','VISITOR.ENTRY.UPDATE',
    'NOTICE.POST.VIEW'
);

INSERT INTO role_permission (role, permission_id)
SELECT 'COMMITTEE', id FROM permission WHERE code IN (
    'USER.PROFILE.VIEW','USER.ACCOUNT.VIEW',
    'FLAT.DIRECTORY.VIEW',
    'VISITOR.ENTRY.VIEW','VISITOR.ENTRY.EXPORT',
    'NOTICE.POST.CREATE','NOTICE.POST.VIEW','NOTICE.POST.UPDATE','NOTICE.POST.DELETE',
    'COMPLAINT.TICKET.VIEW','COMPLAINT.TICKET.EXPORT','COMPLAINT.RESOLUTION.APPROVE','COMPLAINT.RESOLUTION.REJECT'
);

INSERT INTO role_permission (role, permission_id)
SELECT 'ADMIN', id FROM permission WHERE code IN (
    'USER.PROFILE.VIEW','USER.PROFILE.UPDATE',
    'USER.ACCOUNT.CREATE','USER.ACCOUNT.VIEW','USER.ACCOUNT.UPDATE','USER.ACCOUNT.DELETE',
    'FLAT.DIRECTORY.VIEW','FLAT.STRUCTURE.CREATE','FLAT.STRUCTURE.UPDATE','FLAT.STRUCTURE.DELETE',
    'VISITOR.ENTRY.VIEW','VISITOR.ENTRY.DELETE','VISITOR.ENTRY.EXPORT',
    'NOTICE.POST.CREATE','NOTICE.POST.VIEW','NOTICE.POST.UPDATE','NOTICE.POST.DELETE',
    'COMPLAINT.TICKET.VIEW','COMPLAINT.TICKET.EXPORT','COMPLAINT.RESOLUTION.APPROVE','COMPLAINT.RESOLUTION.REJECT',
    'SOCIETY.SETTINGS.VIEW','SOCIETY.SETTINGS.UPDATE',
    'SYSTEM.PERMISSION.VIEW','SYSTEM.PERMISSION.UPDATE'
);
```

### Backend Enforcement Pattern

Permissions are **not** embedded in the JWT (keep the token to `userId`, `role`, `societyId`/`complexId` only) — otherwise a permission change won't take effect until the token expires. Resolve per-request from a cached lookup:

```kotlin
@Service
class PermissionService(private val rolePermissionRepo: RolePermissionRepository) {
    @Cacheable("rolePermissions") // evict this cache key on any role_permission write
    fun getPermissionsForRole(role: String): Set<String> =
        rolePermissionRepo.findByRole(role).map { it.permission.code }.toSet()

    fun hasPermission(role: String, code: String): Boolean =
        getPermissionsForRole(role).contains(code)
}
```

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresPermission(val code: String)

@Aspect
@Component
class PermissionAspect(private val permissionService: PermissionService) {
    @Before("@annotation(requiresPermission)")
    fun check(joinPoint: JoinPoint, requiresPermission: RequiresPermission) {
        val role = SecurityContextHolder.getContext().authentication.authorities
            .first().authority.removePrefix("ROLE_")
        if (!permissionService.hasPermission(role, requiresPermission.code)) {
            throw AccessDeniedException("Missing permission: ${requiresPermission.code}")
        }
    }
}
```

```kotlin
@RestController
@RequestMapping("/api/visitors")
class VisitorController(private val visitorService: VisitorService) {
    @PostMapping
    @RequiresPermission("VISITOR.ENTRY.CREATE")
    fun logVisitor(@RequestBody req: VisitorCreateRequest) = visitorService.create(req)

    @PatchMapping("/{id}/approve")
    @RequiresPermission("VISITOR.APPROVAL.APPROVE")
    fun approve(@PathVariable id: UUID) = visitorService.approve(id) // ownership check inside service
}
```

### Flutter Side
On login, the API returns the resolved permission list (`Set<String>`) alongside the JWT. The auth BLoC stores it and uses it to conditionally show/hide UI (e.g. render the "Approve" button only if `permissions.contains('VISITOR.APPROVAL.APPROVE')`). This is UX-only — the backend `@RequiresPermission` check remains the real security boundary.

---

## Module Relationships

```
Complex (optional) → Society → Wing → Flat → AppUser
                                          ↑        ↓
                                   VisitorEntry, Notice, Complaint

RolePermission (role string) → Permission (independent of AppUser, resolved at request time)
```

- `VisitorEntry` links to both `Flat` (who it's for) and `AppUser` (the Guard who logged it, and the Resident who responded).
- `Notice` and `Complaint` reference the `AppUser` who created them.
- `RolePermission` maps a **role string** to permissions — not tied to a specific user — resolved at request time via the logged-in user's role.
- Everything downstream (Visitor, Notice, Complaint) depends on `Flat` and `AppUser` existing first, which is why the build order is `Complex → Society → Wing → Flat → AppUser` before any feature module.

---

## Backend Development Plan (Spring Boot)

### Module Structure (package-by-feature, layered within each feature)
Each feature package is internally layered Clean-Architecture style: `domain` (entities + framework-free repository interfaces), `application` (services/use cases), `infrastructure` (Spring Data JPA repository + an adapter implementing the domain interface), `interfaces` (REST controllers + DTOs). `config/`, `security/`, and `common/` stay flat — they're cross-cutting, not features.
```
org.devvikram.societymanagement
├── config/                # SecurityConfig, JwtConfig, CacheConfig
├── security/               # JwtAuthFilter, UserDetailsServiceImpl, PermissionAspect
├── common/                 # ApiResponse wrapper, GlobalExceptionHandler, enums
├── user/
│   ├── domain/              # AppUser entity, Role/ResidentType enums, AppUserRepository (interface)
│   ├── application/         # AppUserService (pending)
│   ├── infrastructure/      # SpringDataAppUserRepository + AppUserRepositoryImpl
│   └── interfaces/          # AppUserController, DTOs (pending)
├── society/                 # Complex, Society, Wing, Flat, ParkingSlot, StaffMember
│   ├── domain/
│   ├── application/         # (pending)
│   ├── infrastructure/
│   └── interfaces/          # (pending)
├── visitor/                 # domain/application/infrastructure/interfaces scaffolded, empty
├── notice/                  # domain/application/infrastructure/interfaces scaffolded, empty
└── complaint/                # domain/application/infrastructure/interfaces scaffolded, empty
```
Domain repository interfaces declare plain `save`/`findById`/`findAll` (+ custom finders like `findByPhone`) with no Spring Data types. The infrastructure adapter (`XRepositoryImpl`) implements the domain interface by delegating to a `XJpaRepository : JpaRepository<X, UUID>`. Application/service code depends only on the domain interface — Spring wires the concrete adapter automatically since it's the sole implementation.

### Build Order
1. **Auth & Permission System** — JWT auth, `permission`/`role_permission` tables + seed data (Flyway), `PermissionService` + `@RequiresPermission` aspect, `POST /auth/login` (returns JWT + resolved permission list), Admin-only `POST /users` (gated by `USER.ACCOUNT.CREATE`)
2. **Society structure** — `Complex → Society → Wing → Flat → AppUser` entities + repositories, verified persistence before moving to next entity; resident-to-flat mapping; society settings
3. **Visitor Management API** — CRUD + status transitions (`VISITOR.ENTRY.*`, `VISITOR.APPROVAL.*`), FCM push trigger on new entry, atomic conditional update for multi-resident race handling
4. **Notices API** — CRUD, permission-gated posting (`NOTICE.POST.*`)
5. **Complaints API** — CRUD + status workflow (`COMPLAINT.TICKET.*`, `COMPLAINT.RESOLUTION.*`)

### Entity build order
```
Complex (optional) → Society → Wing → Flat → AppUser → ParkingSlot → StaffMember
```
✅ Done — all seven entities exist under `society/domain` and `user/domain`, each with a domain repository interface + `infrastructure` JPA adapter (see Module Structure above), and a `@DataJpaTest` repository test verifying persistence against the real local Postgres (16/16 passing), including negative tests for the `flat(wing_id, flat_number)` unique constraint and the `app_user.phone` unique constraint. `ParkingSlot`/`StaffMember` persist correctly with no service/controller logic yet — workflow is Phase 2, per plan.

### Gradle setup notes (Spring Boot 4 specific)
- Boot 4 renamed several starters: `spring-boot-starter-web` → `spring-boot-starter-webmvc`, `spring-boot-starter-aop` → `spring-boot-starter-aspectj`. Flyway now requires the explicit `spring-boot-starter-flyway` starter (bare `flyway-core` no longer auto-configures).
- Use `jjwt-orgjson` (not `jjwt-jackson`) for JWT — the project is on Jackson 3 (`tools.jackson`) via Boot 4, and jjwt's Jackson 3 support isn't merged yet; `jjwt-orgjson` has zero Jackson dependency, avoiding the conflict.
- **Removed from the IntelliJ-generated build:** GraphQL codegen plugin (`com.netflix.dgs.codegen` — not building GraphQL, and it collides with the Hibernate ORM plugin's own `generateJava` task, causing a build failure), Lombok (redundant with Kotlin), GraalVM native-image plugin (adds complexity, conflicts with AOP/dynamic proxies used by `@RequiresPermission`), Spring Modulith (`spring-modulith-starter-core`/`-jpa`/`-test` — requires an `event_publication` table we don't use; reconsider in Phase 2 once module boundaries are stable), `spring-boot-docker-compose` (project uses local PostgreSQL via pgAdmin4, not Docker — remove the dependency and the generated `compose.yaml`, or set `spring.docker.compose.enabled=false`).
- `application.properties`: `spring.jpa.hibernate.ddl-auto=validate` (schema owned by Flyway, not Hibernate auto-DDL); `spring.flyway.locations=classpath:db/migration`.
- Migrations live at `src/main/resources/db/migration/V1__init_schema.sql` (full schema) and `V2__seed_permissions.sql` (permission catalog + role mappings) — both must exist for Flyway to create any tables; an empty `db/migration` folder means the app boots but no tables exist yet.

---

## Frontend Development Plan (Flutter)

### Folder Structure (Clean Architecture + BLoC)
```
lib/
├── core/
│   ├── network/     # Dio client, interceptors (JWT attach, refresh)
│   ├── di/          # get_it / injectable setup
│   ├── routing/     # go_router with permission-based redirect guards
│   └── theme/
├── features/
│   ├── auth/
│   │   ├── data/        # models, datasource, repo_impl
│   │   ├── domain/      # entities, repo interface, usecases
│   │   └── presentation/ # bloc, screens, widgets
│   ├── visitor/
│   ├── notice/
│   ├── complaint/
│   └── flat_directory/
└── main.dart
```

### Role-Based UI Approach
Single login screen → post-login, `go_router` redirect checks `user.role` and routes to:
- `ResidentHomeShell`
- `GuardHomeShell`
- `CommitteeHomeShell`
- `AdminHomeShell`

Each shell shows a different bottom nav; underlying feature screens are shared where possible (e.g. `VisitorEntry` domain entity/BLoC is shared — Guard sees a "log entry" form, Resident sees an "approve/reject" list, built as different widgets over the same data layer). The permission list returned at login drives fine-grained UI visibility within each shell (e.g. hiding "Approve" for a resident who isn't on that flat).

### Build Order
1. Project skeleton — Clean Architecture folders, DI, Dio + JWT interceptor
2. Auth — login screen wired to real API, permission-based routing to 4 home shells
3. Visitor Management — Guard log-entry form, Resident approve/reject screen, FCM push handling, "already responded" state
4. Notices — list + post screen (permission-gated)
5. Complaints — raise (Resident), view + update status (Committee/Admin)
6. Polish — error/empty states, validation, app icon/splash, signed build

---

## Milestones & Suggested Billing Checkpoints

| Milestone | Deliverable |
|---|---|
| **M1 — Backend Foundation** | Postman-testable auth + permission system + user management API |
| **M2 — Flutter Skeleton** | Installable APK; each role logs in, lands on distinct dashboard |
| **M3 — Visitor Management** | End-to-end demo: guard logs visitor → resident gets push → approves → guard sees status update |
| **M4 — Notices** | Notice post/view working, permission-gated |
| **M5 — Complaints** | Full complaint lifecycle working |
| **M6 — Polish & Handoff** | Client-ready signed MVP build + README |

M1+M2 and M6 are natural payment checkpoints; M3 is typically the most convincing milestone to demo to the client.

---

## Current Status (as of last session)

- ✅ Gradle project created in IntelliJ IDEA (`org.devvikram.societymanagement`), Spring Boot 4.1.1, Kotlin 2.3.21
- ✅ Dependencies resolved: Security, AspectJ (AOP), Flyway starter, Cache/Caffeine, JJWT (api/impl/orgjson), PostgreSQL driver
- ✅ Removed: GraphQL codegen plugin, Spring Modulith, Docker Compose auto-start, GraalVM native-image plugin
- ✅ Local PostgreSQL database `society_management_db` created via pgAdmin4
- ✅ `application.properties` configured — datasource, `ddl-auto=validate`, Flyway locations, JWT secret placeholder
- ✅ App boots cleanly — Tomcat starts on port 8080, Hikari connects, Hibernate `EntityManagerFactory` initializes without errors
- ✅ `V1__init_schema.sql` and `V2__seed_permissions.sql` created and applied — full schema + permission catalog/role mappings exist in the database
- ✅ Backend package skeleton created — package-by-feature with `domain`/`application`/`infrastructure`/`interfaces` layers per feature (see Module Structure above); `config/`, `security/`, `common/` stubbed; `visitor/`, `notice/`, `complaint/` scaffolded empty
- ✅ JPA entities + repositories for `Complex → Society → Wing → Flat → AppUser → ParkingSlot → StaffMember`, all verified with `@DataJpaTest` repository tests against the real local Postgres (16/16 passing)
- ✅ `user/application/AppUserService` — createUser (hashes password via BCrypt, validates the one-of-society/complex scope rule, rejects duplicate phone, resolves society/complex/flat references), getById/getByPhone/getAll, updateProfile, deactivate. Backed by `config/PasswordEncoderConfig` (`BCryptPasswordEncoder` bean).
- ✅ `society/application` — `ComplexService` (create/get/getAll/updateSettings; reserved, no delete — no Complex Admin role confirmed yet), `SocietyService` (create/get/getAll/updateSettings; resolves optional `complexId`), `WingService` (addWing/getById/listBySociety/renameWing/deleteWing), `FlatService` (addFlat/getById/listByWing/updateFlatNumber/deleteFlat; rejects duplicate flat numbers within a wing pre-flight, in addition to the DB unique constraint). `WingRepository`/`FlatRepository` domain interfaces gained `findBySocietyId`/`findByWingId` and `deleteById` to support this (with matching Spring Data derived-query methods on the Jpa interfaces). No `ParkingSlot`/`StaffMember` services — still Phase 2, schema-only per plan.
- ✅ All application services unit-tested against in-memory fakes of the domain repository interfaces (no mocking library needed — the ports are plain Kotlin). Full suite: 41/41 passing, including the full `@SpringBootTest` context load.
- ⏳ **Next:** both `interfaces/` layers (`user`, `society`) are still empty — no REST controllers yet. Then auth (`POST /auth/login`, `JwtAuthFilter`), then wiring `PermissionService` + `@RequiresPermission` onto one endpoint (`POST /users`) as a proof of concept. Note: Spring Boot 4 relocated test-slice classes into per-feature modules — `@DataJpaTest` is now `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `TestEntityManager` is `org.springframework.boot.jpa.test.autoconfigure.TestEntityManager`, `@AutoConfigureTestDatabase` is `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase` — not the old `org.springframework.boot.test.autoconfigure.*` paths from Boot 3. Also note: Spring Security 7's `PasswordEncoder.encode()` return type reads as nullable to the Kotlin compiler (JSpecify `@Contract` annotation, not a JSR-305 `@Nullable`) — a `!!` assertion on the call is needed and safe.

---

## Open Decisions
- What specifically gets shared at the Complex level (amenities, notices, a common visitor log) — deferred until confirmed with client; same nullable-FK + scope-check pattern used for Guard applies to any future table.