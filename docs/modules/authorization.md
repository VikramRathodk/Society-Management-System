# Authorization & Roles (`authorization`)

Dynamic roles and hierarchical permission codes. Status: ✅ built (M1, dynamic roles in V3).

## Concepts
- **Permission codes** are `MODULE.RESOURCE.ACTION` (e.g. `COMPLAINT.TICKET.VIEW`), seeded by migrations
  into the `permission` table. Constants used in code live in `Permissions`; `PermissionsCatalogTest`
  fails if a constant has no row.
- **Roles** are rows in `role`: scope `PLATFORM` or `SOCIETY`, optional `requires_flat` (e.g. RESIDENT),
  `all_permissions` (SUPER_ADMIN). Custom roles can be created per platform.
- **Inheritance** (`role_inheritance`): a role gets the permissions of the roles below it.
  Built-in: `SUPER_ADMIN > ADMIN > SECRETARY > COMMITTEE > RESIDENT`, and `SECRETARY > GUARD`.
- **Scope**: a user holds a role per society (`user_role`). Flat-tied roles act only on their flat's
  records; other society roles act society-wide. `Access` answers `can`, `canSocietyWide`, `canOnFlat`,
  `flatsWith`, and rank checks (`outranks`, `canGrant`, `canManage`).
- `@RequiresPermission` is the coarse gate (held anywhere); controllers then check the specific society
  or flat through `AccessService.current()`.

## Endpoints

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/api/permissions` | `SYSTEM.PERMISSION.VIEW` | Full catalog |
| GET | `/api/roles` | `SYSTEM.ROLE.VIEW` | |
| GET | `/api/roles/assignable?societyId=` | any role-assigning user | Roles the caller may hand out there |
| GET | `/api/roles/{code}` | `SYSTEM.ROLE.VIEW` | |
| POST | `/api/roles` | `SYSTEM.ROLE.CREATE` (platform) | Custom role |
| PATCH | `/api/roles/{code}` | `SYSTEM.ROLE.UPDATE` (platform) | |
| PUT | `/api/roles/{code}/permissions` | `SYSTEM.PERMISSION.UPDATE` (platform) | Replace direct grants |
| PUT | `/api/roles/{code}/inherits` | `SYSTEM.ROLE.UPDATE` (platform) | Replace inherited roles |
| DELETE | `/api/roles/{code}` | `SYSTEM.ROLE.DELETE` (platform) | |

Role writes are platform-wide, so they need the permission at platform scope (SUPER_ADMIN).

## Tables
`permission`, `role`, `role_inheritance`, `role_permission`. Role lookups are cached (Caffeine,
10 min TTL — bounds staleness after manual SQL edits).

## Adding permissions for a new module
1. Insert the codes in the module's migration, and grant them to the lowest built-in role that should
   hold them (higher roles inherit).
2. Add constants to `Permissions`.
3. Document the role matrix in the module's doc.
