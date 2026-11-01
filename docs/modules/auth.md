# Auth (`auth`)

Login and request authentication. Status: ✅ built (M1).

## What it does
- Phone + password login returns a JWT. The token carries identity only (phone, user id); roles and
  permissions are loaded on every request, so role changes apply immediately.
- `JwtAuthFilter` authenticates each request from `Authorization: Bearer <token>` and refuses
  deactivated accounts.
- Missing, invalid or expired tokens answer 401; a valid token without the permission answers 403.
  Both use the shared `ErrorResponse` body.

## Endpoints

| Method | Path | Access | Notes |
|---|---|---|---|
| POST | `/api/auth/login` | public | `{phone, password}` → `{token, ...}`. Wrong credentials → 401, deactivated → 403 |

## Configuration
`JWT_SECRET` (at least 32 bytes; required in the `prod` profile), `JWT_EXPIRATION_MS` (default 24 h).

## Code
`auth/controller/AuthController`, `auth/service/AuthService`, `auth/security/` (`SecurityConfig`,
`JwtService`, `JwtAuthFilter`, `PasswordEncoderConfig`).

## Known limits
- Tokens stay valid until they expire after a password change (stateless JWT).
- No forgot-password / OTP flow (blocked on the identity-proof decision).
