-- Enable UUID generation (required for gen_random_uuid())
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