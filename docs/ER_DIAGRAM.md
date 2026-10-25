# ER Diagram

Generated from the JPA entities and the Flyway migrations (`V1`–`V7`). `notice` and `complaint` exist in
the schema (V1) but have no JPA entity yet.

```mermaid
erDiagram
    complex ||--o{ society : "groups (optional)"
    society ||--o{ wing : "has (cascade)"
    wing ||--o{ flat : "has (cascade)"

    society ||--o{ parking_slot : has
    flat |o--o{ parking_slot : "assigned (optional)"

    society ||--o{ staff_member : employs
    flat |o--o{ staff_member : "works for (optional)"

    users ||--o{ user_role : "holds (cascade)"
    role ||--o{ user_role : "assigned as"
    society |o--o{ user_role : "scoped to (null = PLATFORM)"
    flat |o--o{ user_role : "resident of (requires_flat)"

    role ||--o{ role_permission : grants
    permission ||--o{ role_permission : "granted by"
    role ||--o{ role_inheritance : "inherits"
    role ||--o{ role_inheritance : "inherited by"

    society ||--o{ visitor_entry : "visited"
    flat ||--o{ visitor_entry : "visitor for"
    users ||--o{ visitor_entry : "logged_by (guard)"
    users |o--o{ visitor_entry : "responded_by"
    stored_photo |o..o{ visitor_entry : "photo_key (logical, no FK)"

    society ||--o{ notice : has
    users ||--o{ notice : posted_by
    society ||--o{ complaint : has
    users ||--o{ complaint : raised_by
    users |o--o{ complaint : updated_by

    complex {
        bigint id PK
        varchar name
        varchar address
        timestamp created_at
    }
    society {
        bigint id PK
        bigint complex_id FK "nullable: standalone"
        varchar name
        varchar address
        timestamp created_at
    }
    wing {
        bigint id PK
        bigint society_id FK
        varchar name "A, B..."
    }
    flat {
        bigint id PK
        bigint wing_id FK
        varchar flat_number "UNIQUE(wing_id, flat_number)"
    }
    parking_slot {
        bigint id PK
        bigint society_id FK
        bigint flat_id FK "nullable: unassigned"
        varchar slot_number "UNIQUE(society_id, slot_number)"
        varchar vehicle_type
        timestamp created_at
    }
    staff_member {
        bigint id PK
        bigint society_id FK
        bigint flat_id FK "nullable: society-wide"
        varchar name
        varchar phone
        varchar photo_url
        varchar staff_type
        varchar id_proof_url
        boolean is_active
        timestamp created_at
    }
    users {
        bigint id PK
        varchar name
        varchar phone UK
        varchar email
        varchar password_hash
        varchar fcm_token
        boolean is_active
        timestamp created_at
    }
    role {
        bigint id PK
        varchar code UK
        varchar name
        varchar description
        varchar scope_level "PLATFORM | SOCIETY"
        boolean requires_flat
        boolean all_permissions
        boolean is_system
        timestamp created_at
    }
    permission {
        bigint id PK
        varchar module
        varchar feature
        varchar subfeature
        varchar action
        varchar code UK
        varchar description
    }
    role_permission {
        bigint role_id FK
        bigint permission_id FK
    }
    role_inheritance {
        bigint role_id FK
        bigint inherited_role_id FK
    }
    user_role {
        bigint id PK
        bigint user_id FK
        bigint role_id FK
        bigint society_id FK "null only for PLATFORM roles"
        bigint flat_id FK "set only if role.requires_flat"
        varchar resident_type
        timestamp created_at
    }
    visitor_entry {
        bigint id PK
        bigint society_id FK
        bigint flat_id FK
        bigint logged_by FK
        bigint responded_by FK
        varchar visitor_name
        varchar visitor_phone
        varchar purpose
        varchar photo_key
        varchar status "PENDING|APPROVED|REJECTED|ENTERED|EXITED"
        boolean responded_as_override
        timestamp logged_at
        timestamp responded_at
        timestamp entered_at
        timestamp exited_at
    }
    stored_photo {
        bigint id PK
        varchar content_type
        int size_bytes
        bytea data
        timestamp created_at
    }
    notice {
        bigint id PK
        bigint society_id FK
        bigint posted_by FK
        varchar title
        text description
        timestamp created_at
    }
    complaint {
        bigint id PK
        bigint society_id FK
        bigint raised_by FK
        bigint updated_by FK
        varchar category
        text description
        varchar status "OPEN|IN_PROGRESS|RESOLVED|CLOSED"
        timestamp created_at
        timestamp updated_at
    }
```

## How the pieces fit

- **Property hierarchy:** `complex` (optional) → `society` → `wing` → `flat`. Deleting a society cascades
  to its wings and flats; deleting a wing cascades to its flats.
- **Identity vs. authorization:** `users` is identity only. Everything a user can do lives in
  `user_role`: one row per (user, role, society, flat) scope. A user can be a resident in one society
  and a committee member in another.
- **Dynamic RBAC:** `role` ↔ `permission` is many-to-many through `role_permission`. `role_inheritance`
  is a self-referencing many-to-many on `role`, so a higher role picks up the permissions of the roles it
  inherits. `all_permissions = true` (SUPER_ADMIN) grants everything.
- **Visitors:** a `visitor_entry` belongs to a flat (and its society). It references two users: the guard
  who logged it (`logged_by`) and the resident or overriding authority who answered (`responded_by`).
  `photo_key` holds a `stored_photo.id` as a string, with no foreign key.
- **Phase 2 tables:** `parking_slot` and `staff_member` have their schema and entities, but no workflow
  yet. `notice` and `complaint` are schema-only.
