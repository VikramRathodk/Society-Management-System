# Notifications (`notification`)

Push delivery used by the feature modules. Status: ✅ built (FCM 2026-10-30).

## How it works
- Feature services publish an event; a `@TransactionalEventListener` in the feature module runs after
  commit, works out the recipients' tokens and calls `PushNotifier.send(tokens, title, body, data)`.
  Delivery is best-effort: failures are logged, never undo the action.
- `PUSH_PROVIDER=logging` (default) logs instead of sending; `fcm` uses `FcmPushNotifier` with the
  service-account JSON from `FIREBASE_CREDENTIALS`.
- Tokens Firebase reports as dead are published as `InvalidPushTokens` and cleared by the user module.
- Every message carries `data.type` plus ids; clients map the type to a screen.

## Message types

| `type` | Data | Module |
|---|---|---|
| `VISITOR_LOGGED` | `visitorId` | [visitor](visitor.md) |
| `VISITOR_RESPONDED` | `visitorId`, `status`, `override` | [visitor](visitor.md) |
| `NOTICE_POSTED` | `noticeId` | [notice](notice.md) |
| `COMPLAINT_RAISED` | `complaintId` | [complaint](complaint.md) |
| `COMPLAINT_STATUS_CHANGED` | `complaintId`, `status` | [complaint](complaint.md) |
| `MAINTENANCE_BILL_ISSUED` | `billId`, `cycleId` | [maintenance](maintenance.md) |

Setup and payload details: [FIREBASE_SETUP.md](../FIREBASE_SETUP.md).

Billing pushes: `PLATFORM_INVOICE`, `PLATFORM_PAYMENT`, `PLATFORM_INVOICE_REMINDER` (`invoiceId`) and
`SUBSCRIPTION_STATUS` (`status`) — see [subscription](subscription.md).

## Email

`Mailer.send(MailMessage(to, subject, text, html?))` answers `MailResult(sent, error)` and never throws.
`MAIL_PROVIDER=log` (default) logs the mail (`LoggingMailer`); `smtp` sends through `SmtpMailer` with
`SMTP_HOST`, `SMTP_PORT` (587, STARTTLS), `SMTP_USERNAME`, `SMTP_PASSWORD` and the sender `MAIL_FROM` —
any provider's SMTP endpoint (SES, SendGrid, Zoho, Gmail with an app password). Timeouts are 10 s. Its
first user is platform billing ([subscription](subscription.md)), which keeps its own `email_log`.
