# Society Management System — Backend

REST API for running a residential society: gate visitor management with resident approval, a
notice board, and complaint tracking, with push notifications through Firebase Cloud Messaging.
Access is controlled by dynamic, per-society roles built on hierarchical permission codes.

The MVP client is the Angular web app (separate repository, `Society Management - Web`).

| | |
|---|---|
| Language / framework | Kotlin 2.3, Spring Boot 4.1, Java 17+ |
| Database | PostgreSQL, schema managed by Flyway (`src/main/resources/db/migration`) |
| Auth | Phone + password login, stateless JWT (`Authorization: Bearer …`) |
| Push | Firebase Cloud Messaging (optional; logs only by default) |

## What's in the MVP

| Area | Highlights |
|---|---|
| Users & roles | Super admin, admin, secretary, committee, guard and resident roles; custom roles per society; activate, deactivate, change and reset passwords |
| Society structure | Complexes, societies, wings, flats, flat directory |
| Visitors (M3) | Guard logs a visitor with a photo → every resident of the flat gets a push → first response wins → guard sees the result; admin override, entry/exit, history, CSV export |
| Notices (M4) | Committee posts, everyone reads, push on post; authors edit their own posts, secretaries moderate |
| Complaints (M5) | Residents raise tickets for their flat; committee moves them Open → In progress → Resolved → Closed; activity timeline, CSV export, push on updates |

Full module list and status: [docs/MODULES.md](docs/MODULES.md). Design, schema and the permission
matrix: [society_planning.md](society_planning.md). Entity diagram: [docs/ER_DIAGRAM.md](docs/ER_DIAGRAM.md).

## Run locally

1. Create a PostgreSQL database named `society_management_db` (default login `postgres` / `123456`;
   override with `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).
2. Start the API — Flyway creates the schema on first start:

   ```bash
   ./gradlew bootRun
   ```

   It listens on `http://localhost:8080`.
3. Load test data (optional, empty database only):

   ```bash
   psql -h localhost -U postgres -d society_management_db -f scripts/dev-seed.sql
   ```

   Every seeded user's password is `Pass@123`; `9000000999` is the super admin and `9000000616` the
   secretary of *Avengers Tower*. The full list is at the top of the script.

Push stays in logging mode (notifications are written to the log, no Firebase needed) unless
`PUSH_PROVIDER=fcm` — see [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md).

## Tests

```bash
./gradlew test
```

Unit tests run against in-memory fakes; repository and integration tests run against the local
PostgreSQL above. `ModuleDependencyTest` fails the build if one module uses another it isn't allowed to.

The Postman collection in [postman/](postman/SocietyManagement.postman_collection.json) walks every
endpoint in order against the dev seed, with assertions; run it with the Collection Runner or
`newman run postman/SocietyManagement.postman_collection.json`.

## Build

```bash
./gradlew bootJar
```

Produces `build/libs/societyManagement-1.0.0.jar` (ignore the `-plain` jar). Run it with Java 17 or newer:

```bash
java -jar societyManagement-1.0.0.jar --spring.profiles.active=prod
```

## Deploy

Run with the `prod` profile (`SPRING_PROFILES_ACTIVE=prod`). It removes the dev defaults for the
database and the JWT secret, so the app will not start until these are set:

| Variable | Required | Purpose |
|---|---|---|
| `DB_URL` | yes | e.g. `jdbc:postgresql://db-host:5432/society_management_db` |
| `DB_USERNAME`, `DB_PASSWORD` | yes | Database login. The user needs rights to create tables (Flyway migrates on start). |
| `JWT_SECRET` | yes | Token signing key, at least 32 bytes: `openssl rand -base64 48`. Changing it logs everyone out. |
| `JWT_EXPIRATION_MS` | no | Token lifetime, default `86400000` (24 h) |
| `BOOTSTRAP_ADMIN_PHONE`, `BOOTSTRAP_ADMIN_PASSWORD` | first start | Creates the first super admin — see below |
| `BOOTSTRAP_ADMIN_NAME` | no | Its display name, default `Super Admin` |
| `PUSH_PROVIDER` | no | `fcm` to send real push notifications; default `logging` |
| `FIREBASE_CREDENTIALS` | with `fcm` | Path to the Firebase service-account JSON (default `secrets/firebase-service-account.json`); blank uses Google Application Default Credentials |
| `PUSH_ANDROID_CHANNEL` | no | Android notification channel id, default `society_default` |
| `REPORT_ZONE` | no | Time zone for export date ranges, default `Asia/Kolkata` |
| `SERVER_PORT` | no | Default `8080` |

**First super admin.** A new database has no users, and every account is created by another user.
When the users table is empty and `BOOTSTRAP_ADMIN_PHONE` / `BOOTSTRAP_ADMIN_PASSWORD` (8–72
characters) are set, the app creates a super admin on start. With any user present it does nothing,
so the variables can be removed afterwards. Log in, change that password, then create the societies,
their structure and their admins from the web app.

**Web app and CORS.** The API has no CORS configuration, so serve the web app from the same origin and
forward `/api/` to the backend, e.g. with nginx:

```nginx
server {
    listen 443 ssl;
    server_name society.example.com;
    # ssl_certificate ... ;

    root /var/www/society-web/browser;   # `npm run build` output of the web app
    location / { try_files $uri $uri/ /index.html; }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 6m;          # visitor photos are up to 5 MB
    }
}
```

The `prod` profile trusts the proxy's `X-Forwarded-*` headers. Browsers only allow web push on HTTPS
(or `localhost`), so the site needs a certificate for notifications to work.

**Data.** Visitor photos are stored in the database, so a regular PostgreSQL backup (`pg_dump`) covers
everything. Never edit an applied migration; add a new `V<n>__*.sql` instead.

## Project layout

```
src/main/kotlin/org/devvikram/societymanagement/
├── common/         config, error responses, CSV export, photo storage
├── auth/           login, JWT, Spring Security
├── authorization/  roles, permission codes, access checks
├── society/        complexes, societies, wings, flats
├── user/           users, role assignments, profile, first-admin bootstrap
├── notification/   push (FCM or logging)
├── visitor/        gate visitor flow (M3)
├── notice/         notice board (M4)
└── complaint/      complaints (M5)
```

Each module has `controller/ dto/ service/ model/ repository/`.

## Known limits (MVP)

- No self-service "forgot password" (needs an OTP channel); an admin resets it instead.
- No invitations or society onboarding flow yet; the super admin creates accounts.
- A password change does not log out other sessions: issued tokens stay valid until they expire.
  Deactivating an account, or changing its roles, takes effect on the next request.
- Complex-level sharing (common notices, amenities) is not defined yet; complex endpoints are platform-only.
