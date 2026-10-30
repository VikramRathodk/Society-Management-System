# Firebase (FCM) Setup & Integration

Visitor, notice and complaint pushes (M3/M4/M5) are delivered through Firebase Cloud Messaging.
Every module talks to `notification/PushNotifier`. Which implementation runs is chosen by
`app.push.provider`, and callers never change.

Firebase project: **`society-managment-8d21f`**.

---

## 1. Status

| Piece | Where | State |
|---|---|---|
| `PushNotifier` interface | `notification/PushNotifier.kt` | ✅ `send(tokens, title, body, data)`, best-effort |
| `LoggingPushNotifier` | same file | ✅ default (`app.push.provider=logging` or unset); logs only |
| `FcmPushNotifier` + `FirebaseConfig` | `notification/` | ✅ active with `app.push.provider=fcm` |
| Dead-token cleanup | `notification/InvalidPushTokens.kt` → `user/service/PushTokenCleanup.kt` | ✅ |
| Push triggers | `visitor/…/VisitorNotifications.kt`, `notice/…/NoticeNotifications.kt`, `complaint/…/ComplaintNotifications.kt` | ✅ after-commit `@TransactionalEventListener` |
| Token storage | `users.fcm_token VARCHAR(512)` (V1, widened in V12) | ✅ one token per user |
| Token registration | `PATCH /api/users/{id}` `{"fcmToken": "..."}` (`USER.PROFILE.UPDATE`) | ✅ blank clears; a token moves between accounts |
| Service-account key | `secrets/` (git-ignored) | ✅ verified: the app boots with `PUSH_PROVIDER=fcm` |
| Web client | Angular app, see its `docs/WEB_PUSH_SETUP.md` | 🟡 VAPID key + fixes pending |
| Flutter client | — | ❌ not started (M2) |
| Real end-to-end push | — | ❌ needs a registered device token |

---

## 2. Firebase Console (one-time, manual)

### 2.1 Project & API
- Project `society-managment-8d21f` exists.
- **Project settings → Cloud Messaging**: "Firebase Cloud Messaging API (V1)" must show
  **Enabled**. Google has shut down the legacy server-key API, so don't use it.
- Later, create a second project for dev, so dev tokens can never receive prod pushes.

### 2.2 Backend service-account key
1. **Project settings → Service accounts → Firebase Admin SDK → Generate new private key**.
2. Save the JSON as `secrets/firebase-service-account.json` (the default path). These `.gitignore` rules keep it out of git:
   ```
   ### Firebase / secrets ###
   secrets/
   *firebase-adminsdk*.json
   ```
   Check it is ignored with `git check-ignore -v secrets/*.json`.
3. If the key ever leaks: Google Cloud Console → IAM → Service accounts → *firebase-adminsdk* →
   Keys → delete it, then generate a new one.

### 2.3 Client apps
| Client | Steps |
|---|---|
| **Flutter (Android + iOS)** | `dart pub global activate flutterfire_cli` → `flutterfire configure --project=society-managment-8d21f`. Add `firebase_core` + `firebase_messaging`. Create the Android channel **`society_default`** (see 3.2). |
| **iOS extra** | Apple Developer → Keys → **APNs Auth Key (.p8)** → upload under **Cloud Messaging → Apple app configuration**. In Xcode, enable *Push Notifications* + *Background Modes → Remote notifications*. |
| **Angular web** | Web app already registered (`src/environments/firebase.ts`). Still to do: **Cloud Messaging → Web Push certificates → Generate key pair**, then set `vapidKey`. See the web repo's `docs/WEB_PUSH_SETUP.md`. |

Client config (`firebaseConfig`, `google-services.json`, VAPID public key) is **not** secret.
Only the service-account JSON is secret.

---

## 3. Backend

### 3.1 Dependency (`build.gradle.kts`)
```kotlin
implementation("com.google.firebase:firebase-admin:9.10.0")
```

### 3.2 Configuration (`application.properties`)
```properties
app.push.provider=${PUSH_PROVIDER:logging}                          # logging | fcm
app.firebase.credentials-path=${FIREBASE_CREDENTIALS:secrets/firebase-service-account.json}
app.push.android-channel-id=${PUSH_ANDROID_CHANNEL:society_default} # must match the Flutter channel
```
Run with real pushes (PowerShell):
```powershell
$env:PUSH_PROVIDER = "fcm"
# key is read from secrets/firebase-service-account.json by default
./gradlew bootRun
```
Tests and plain `bootRun` use `logging`, so neither needs Firebase.

### 3.3 How it works
- **`FirebaseConfig`** loads only when `provider=fcm`. It reads the JSON from
  `credentials-path`, or uses Application Default Credentials when the path is blank (for
  example on GCP). The app refuses to start if the path doesn't point to an existing file.
- **`FcmPushNotifier`**:
  - Drops blank and duplicate tokens.
  - Sends in batches of **500** (FCM's multicast limit) with `sendEachForMulticast`: notification
    title/body + string `data`, Android priority HIGH on channel `society_default`, APNs sound
    `default`.
  - Catches and logs every error. A push never fails the action that triggered it.
- **Dead tokens:** only `UNREGISTERED` and `SENDER_ID_MISMATCH` count as permanently dead.
  `INVALID_ARGUMENT` is deliberately excluded: FCM also returns it for a bad payload, and treating
  it as dead would wipe every token. Dead tokens are published as `InvalidPushTokens`.
- **`PushTokenCleanup`** (in `user`) listens for `InvalidPushTokens` and nulls those tokens. It
  runs with `REQUIRES_NEW`, because pushes are sent from after-commit listeners. Joining the
  finished transaction there would mean the update silently never commits.
- **Module rule:** `user` may now depend on `notification` (`ModuleDependencyTest`,
  `society_planning.md`). `notification` still depends on nothing.

### 3.4 Token lifecycle (`UserService.updateProfile`)
| Case | Behaviour |
|---|---|
| Login / app start / token refresh | client `PATCH /api/users/{me}` `{"fcmToken": "<token>"}` (trimmed) |
| Logout | client sends `{"fcmToken": ""}` → stored as `null` |
| `fcmToken` omitted | token unchanged |
| Same token already on another account (shared device) | removed from the other account first |
| FCM reports the token dead | cleared by `PushTokenCleanup` |

### 3.5 Migration (`V12__fcm_token.sql`)
```sql
ALTER TABLE users ALTER COLUMN fcm_token TYPE VARCHAR(512);
CREATE INDEX idx_users_fcm_token ON users (fcm_token) WHERE fcm_token IS NOT NULL;
```

---

## 4. Payload contract (Flutter & Angular)

Every push carries a `notification` (title/body, shown by the OS) and string-only `data`.
Clients route on `data.type` and never parse the title or body.

| `data.type` | Other keys | Sent to | Client opens |
|---|---|---|---|
| `VISITOR_LOGGED` | `visitorId` | active residents of the flat | approvals |
| `VISITOR_RESPONDED` | `visitorId`, `status`, `override` | the guard who logged it (+ residents on override) | visitor log |
| `NOTICE_POSTED` | `noticeId` | society members except the author | notices |
| `COMPLAINT_RAISED` | `complaintId` | society complaint managers | complaint detail |
| `COMPLAINT_STATUS_CHANGED` | `complaintId`, `status` | the raiser (unless they made the change) | complaint detail |

---

## 5. Testing

| Test | Covers | State |
|---|---|---|
| `FcmPushNotifierTest` | no call without tokens, 500-token batching, only dead codes reported, failures swallowed | ✅ |
| `UserServiceTest` | blank token clears, omitted token kept, token moves between accounts | ✅ |
| `UserRepositoryTest` | `clearFcmTokens` / `clearFcmTokensExcept` against Postgres | ✅ |
| `ModuleDependencyTest` | `user → notification` allowed | ✅ |
| Full suite | | ✅ 173/173 |
| Boot with `PUSH_PROVIDER=fcm` + real key | credentials load | ✅ |
| Firebase Console → Messaging → *Send test message* to a device token | client receives | ❌ |
| End to end: guard logs a visitor → resident's push → approve → guard's push | full flow | ❌ |

---

## 6. Deployment

- Mount the JSON as a secret file on the server, and set `FIREBASE_CREDENTIALS` and
  `PUSH_PROVIDER=fcm`. On GCP, leave the path blank and grant the runtime service account
  *Firebase Cloud Messaging API Admin*.
- Use one Firebase project per environment (dev / prod), each with its own key.

---

## 7. Open decisions & known limits

1. **One device per user.** `users.fcm_token` holds one token, so a user on both phone and web
   only gets pushes on the device that registered last. Multi-device needs a
   `user_device(user_id, token, platform, last_seen_at)` table. Deferred until after the M3 demo.
2. **Android channels.** One `society_default` for now. Separate `visitors` (high importance)
   and `notices` channels would need a per-message channel id from the backend.
3. **Sending happens in the request thread** (after commit, but before the response returns):
   about 100–300 ms extra on actions that push. This could move to an async executor later.
4. **Dev vs prod Firebase projects.** Create a second project once there are real users.

---

## 8. Checklist

- [x] Firebase project created, FCM v1 API enabled
- [x] Service-account JSON in `secrets/`, git-ignored
- [x] `firebase-admin` dependency
- [x] `FirebaseConfig`, `FcmPushNotifier`, conditional `LoggingPushNotifier`
- [x] `InvalidPushTokens` + `PushTokenCleanup`; `ModuleDependencyTest` updated
- [x] Blank `fcmToken` clears; token de-duplicated across users
- [x] `V12__fcm_token.sql`
- [x] Tests passing (173/173); app boots with FCM
- [ ] Web: VAPID key + fixes in `docs/WEB_PUSH_SETUP.md` (web repo)
- [ ] Flutter: `flutterfire configure`, APNs key, `society_default` channel
- [ ] Manual end-to-end visitor push verified
- [ ] `society_planning.md` Current Status: M3/M4/M5 push done
