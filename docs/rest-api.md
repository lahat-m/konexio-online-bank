# Konexio Online Bank — REST API

Conventions follow Siva Prasad Reddy's *Spring Boot REST API Best Practices* series ([Part 1](https://www.sivalabs.in/blog/spring-boot-rest-api-best-practices-part-1/), [Part 2](https://www.sivalabs.in/blog/spring-boot-rest-api-best-practices-part-2/), [Part 3](https://www.sivalabs.in/blog/spring-boot-rest-api-best-practices-part-3/), [Part 4](https://www.sivalabs.in/blog/spring-boot-rest-api-best-practices-part-4/)), applied to Konexio's screens and architecture.

Stack: Java 25, Spring Boot 4, Spring Security 7 (custom JWT), PostgreSQL 18.

---

## 1. Conventions

### From the series

- **Status codes by method**
  - `POST` (create) → `201 Created` + `Location` header pointing to the new resource + created DTO in the body.
  - `PUT` (update) → `200 OK`.
  - `GET /{id}` → `200 OK`, or `404 Not Found` if it doesn't exist.
  - `DELETE` → `200 OK` on success.
- **Separate request, command and response types.** Each endpoint has its own request record (e.g. `CreateTransferRequest`). The controller maps it to a command (e.g. `CreateTransferCommand`), which can also carry values the client doesn't send, such as the authenticated customer ID. Entities are never exposed; responses are DTOs.
- **Paginated collections.** Every list endpoint takes `page` (1-based, default `1`) and `size` (default `10`) and returns a `PagedResult`:

  ```json
  {
    "data": [ ... ],
    "totalElements": 15,
    "pageNumber": 1,
    "totalPages": 2,
    "isFirst": true,
    "isLast": false,
    "hasNext": true,
    "hasPrevious": false
  }
  ```

- **Errors as ProblemDetail** (RFC 9457), produced by one `@RestControllerAdvice`.

### Konexio-specific rules

- **Paths:** plural nouns under `/api`.
- **Data formats**
  - IDs are UUIDs (`uuidv7()` in PostgreSQL 18).
  - Money is `{"amount": "3500.00", "currency": "KES"}` — a decimal string, so no precision is lost.
  - Timestamps are ISO-8601 UTC.
- **Headers**

  | Header | Required on |
  |---|---|
  | `Authorization: Bearer <access JWT>` | Every endpoint except registration, login, JWKS and provider callbacks |
  | `Idempotency-Key: <uuid>` | Every request that moves money or opens an account |
  | `Step-Up-Token: <step-up JWT>` | Every request that moves money, closes an account or accepts a loan |

- **Status codes for failures**

  | Code | Meaning |
  |---|---|
  | `400` | Validation error |
  | `401` | Missing, invalid or expired token; bad credentials |
  | `403` | Missing or invalid step-up token |
  | `404` | Not found — also returned for another customer's resources, so IDs can't be probed |
  | `409` | State conflict (e.g. confirming an intent that's already processing) |
  | `410` | Expired (OTP, loan offer) |
  | `422` | Business-rule failure (insufficient funds, limit exceeded, KYC mismatch) |
  | `423` | Credential locked after too many wrong PINs |
  | `429` | Rate limited (`Retry-After` header included) |

---

## 2. Auth Service

| Method | Path | Purpose | Success | Key errors |
|---|---|---|---|---|
| POST | `/api/registrations` | Start sign-up: name, National ID, DOB, phone, email. Runs KYC and sends the OTP. | `201` + `Location: /api/registrations/{id}`, status `OTP_SENT` | `400` validation, `409` phone/ID already registered, `422` KYC mismatch |
| POST | `/api/registrations/{registrationId}/otp-resends` | Resend the OTP | `202 Accepted` | `429` + `Retry-After` |
| POST | `/api/registrations/{registrationId}/otp-verification` | Submit the 6-digit code | `200`, status `OTP_VERIFIED` | `422` wrong code, `410` expired, `429` too many tries |
| PUT | `/api/registrations/{registrationId}/pin` | Set the 4-digit PIN. PUT because repeating it gives the same result. | `200`, status `COMPLETED` + `customerId` | `409` OTP not verified, `422` weak PIN |
| GET | `/api/registrations/{registrationId}` | Resume sign-up, check status | `200` | `404` |
| POST | `/api/tokens` | Log in (`grantType: pin`) or refresh (`grantType: refresh_token`) | `200` + token pair | `401` bad credentials, `423` locked |
| POST | `/api/tokens/revocation` | Log out by revoking the refresh token's family | `200` | `400` |
| POST | `/api/step-up-tokens` | Re-enter the PIN for one intent (`intentType`, `intentId`) | `200` + 2-minute single-use token | `401` wrong PIN, `404` intent, `423` locked |
| POST | `/api/staff/tokens` | Staff login (`username`, `password`) | `200` + 30-minute access token, no refresh token | `401` bad credentials, `423` locked |
| GET | `/.well-known/jwks.json` | Public signing keys for the Banking API | `200`, cacheable | — |

> Tokens aren't addressable resources, so these POSTs return `200` instead of `201` + `Location`. This follows the OAuth convention for token endpoints.

---

## 3. Customers and accounts

| Method | Path | Purpose | Success | Key errors |
|---|---|---|---|---|
| GET | `/api/customers/me` | Profile for the dashboard greeting | `200` | `401` |
| POST | `/api/accounts` | Open an account (`type: MAIN \| SAVINGS`) | `201` + `Location: /api/accounts/{id}` + AccountDTO with account number and KES 0.00 | `409` main account already exists, `422` KYC incomplete |
| GET | `/api/accounts?type=&status=&page=1&size=10` | My accounts, with dormant ones flagged | `200` `PagedResult<AccountSummaryDTO>` | `401` |
| GET | `/api/accounts/{accountId}` | Account details and balance | `200` | `404` |
| GET | `/api/accounts/{accountId}/closure-eligibility` | The 3-check checklist, each with a `fixAction` | `200` `ClosureEligibilityDTO` | `404` |
| DELETE | `/api/accounts/{accountId}?reason=NOT_USED` + `Step-Up-Token` | Close a dormant account. The record is kept with status `CLOSED` (logical delete). | `200` + closure reference | `409` + failing checks in ProblemDetail, `404`, `403` bad step-up |
| GET | `/api/recipients/{accountNumber}` | Name check before a transfer: masked name + `firstTimeRecipient` flag | `200` | `404`, `429` (rate-limited to stop account-number scanning) |

> The closure reason goes in a query parameter because a DELETE request shouldn't carry a body.

Closure eligibility checks:

| Check | Passes when | `fixAction` if failed |
|---|---|---|
| `DORMANT` | Status is `DORMANT` and no customer activity for the configured period | `NONE` |
| `ZERO_BALANCE` | Balance is KES 0.00 | `TRANSFER_BALANCE` |
| `NO_ACTIVE_LOAN` | No `ACTIVE` or `OVERDUE` loan is linked | `REPAY_LOAN` |

---

## 4. Money movement

Deposits, withdrawals and transfers are separate resources (all backed by `payment_intent`). All three follow the wireframe pattern **enter → review → PIN → result**:

1. **Create** — `POST` returns a quote (fee and balance-after), status `PENDING_CONFIRMATION`.
2. **Edit** — optionally `PATCH` the amount or note while still pending.
3. **Confirm** — `POST .../confirmation` with `Step-Up-Token`.
4. **Poll** — `GET` the resource for its final status.

Status lifecycle: `PENDING_CONFIRMATION → PROCESSING → COMPLETED | FAILED | EXPIRED` (or `CANCELLED` via `DELETE` before confirming).

### Deposits

| Method | Path | Purpose | Success | Key errors |
|---|---|---|---|---|
| POST | `/api/deposits` | Create a deposit (`channel: MPESA \| CARD \| AGENT`, amount, target account) | `201` + `Location`, status `PENDING_CONFIRMATION`, fee, balanceAfter | `422` below min / above max |
| PATCH | `/api/deposits/{depositId}` | Change the amount before confirming ("Edit amount") | `200`, re-quoted | `409` already confirmed |
| POST | `/api/deposits/{depositId}/confirmation` | Confirm with PIN. Triggers the M-Pesa STK Push. | `202 Accepted`, status `PROCESSING` | `403` step-up, `409` wrong state |
| GET | `/api/deposits/{depositId}` | Poll status (`COMPLETED` / `FAILED` + reason) | `200` | `404` |
| DELETE | `/api/deposits/{depositId}` | Cancel before confirming | `200` | `409` already processing |

### Withdrawals

| Method | Path | Purpose | Success | Key errors |
|---|---|---|---|---|
| POST | `/api/withdrawals` | Create a withdrawal (`channel: MPESA \| AGENT`) | `201` + `Location` + quote | `422` insufficient funds |
| PATCH | `/api/withdrawals/{withdrawalId}` | Edit the amount | `200` | `409`, `422` |
| POST | `/api/withdrawals/{withdrawalId}/confirmation` | Confirm. Triggers the M-Pesa B2C payout or issues an agent code. | `202 Accepted` | `403`, `409`, `422` |
| GET | `/api/withdrawals/{withdrawalId}` | Poll status | `200` | `404` |
| DELETE | `/api/withdrawals/{withdrawalId}` | Cancel before confirming | `200` | `409` |

### Transfers

| Method | Path | Purpose | Success | Key errors |
|---|---|---|---|---|
| POST | `/api/transfers` | Create a transfer (to account number, amount, note) | `201` + `Location` + quote | `404` recipient, `422` insufficient funds / limit |
| PATCH | `/api/transfers/{transferId}` | Edit the amount or note | `200` | `409`, `422` |
| POST | `/api/transfers/{transferId}/confirmation` | Confirm. Internal transfers post to the ledger immediately. | `200`, status `COMPLETED` + receipt | `403`, `409`, `422` |
| GET | `/api/transfers/{transferId}` | Transfer details | `200` | `404` |
| DELETE | `/api/transfers/{transferId}` | Cancel before confirming | `200` | `409` |

> Deposit and withdrawal confirmations return `202` because M-Pesa finishes later and reports back through a callback. Internal transfers complete in the same request, so they return `200`.

---

## 5. Transactions

| Method | Path | Purpose | Success | Key errors |
|---|---|---|---|---|
| GET | `/api/transactions?accountId=&direction=IN\|OUT&type=&from=&to=&page=1&size=20` | History, matching the All / Money in / Money out / Loans chips | `200` `PagedResult<TransactionDTO>` | `400` bad filter |
| GET | `/api/transactions/{transactionId}` | Receipt details (reference, fee, date, parties) | `200` | `404` |
| GET | `/api/transactions/{transactionId}/receipt` with `Accept: application/pdf` | Downloadable receipt | `200` PDF | `404`, `406` |

---

## 6. Loans (bonus)

| Method | Path | Purpose | Success | Key errors |
|---|---|---|---|---|
| GET | `/api/loan-offers` | Current offers (a short list, so not paginated) | `200` | `422` not eligible (with reason) |
| GET | `/api/loan-offers/{offerId}` | Terms: interest, fee, total, due date | `200` | `404`, `410` offer expired |
| POST | `/api/loans` + `Step-Up-Token` + `Idempotency-Key` | Accept an offer: `{offerId, disburseToAccountId, termsAccepted: true}`. Opens the loan account and pays out KES 10,000. | `201` + `Location: /api/loans/{id}` + LoanDTO with disbursement reference | `409` active loan exists, `410` offer expired, `422` terms not accepted |
| GET | `/api/loans?status=&page=1&size=10` | My loans | `200` `PagedResult<LoanSummaryDTO>` | `401` |
| GET | `/api/loans/{loanId}` | Loan account: owed, due date, repaid so far | `200` | `404` |
| GET | `/api/loans/{loanId}/repayment-schedule` | Installments | `200` | `404` |

Disbursement journal (one DB transaction): `DR loan account 10,000.00 / CR main account 10,000.00`.

---

## 7. Provider callbacks (server-to-server)

These use a separate Spring Security filter chain (IP allowlist + HMAC signature) instead of JWTs. Each one is idempotent on the provider's transaction reference, so a retried callback never posts twice.

| Method | Path | Purpose | Success |
|---|---|---|---|
| POST | `/api/callbacks/mpesa/stk-results` | Deposit result from M-Pesa | `200` acknowledgement |
| POST | `/api/callbacks/mpesa/b2c-results` | Withdrawal result | `200` |
| POST | `/api/callbacks/mpesa/b2c-timeouts` | Withdrawal timeout | `200` |
| POST | `/api/callbacks/card-charges` | Card deposit result | `200` |
| POST | `/api/callbacks/agent-transactions` | Agent cash-in / cash-out confirmation | `200` |

> Each callback returns `200` as soon as it's safely stored; the ledger posting happens afterwards, so the provider doesn't time out and retry.

---

## 8. Staff console

For the bank's own people, on a staff token from `POST /api/staff/tokens`. That token carries `act: STAFF` and a `roles` claim, so every row a staff request writes is attributed to the person who made it — `audit_log.actor_type` says `STAFF` and `actor_id` is their staff id.

| Role | May |
|---|---|
| `OPS` | Find customers, read accounts and statements, follow references into the books, post reversals |
| `COMPLIANCE` | The same reads, plus the audit log and the security trail. **Not** reversals |
| `ADMIN` | Both |

| Method | Path | Purpose | Success | Key errors |
|---|---|---|---|---|
| GET | `/api/staff/customers?q=&page=1&size=10` | Search by part of a name, the end of a phone number, or an email. Blank `q` lists everyone. | `200` `PagedResult<CustomerSummaryDTO>` | `403` wrong role |
| GET | `/api/staff/customers/{customerId}` | Profile plus every account, closed ones included. Writes a `CUSTOMER_VIEWED` audit row. | `200` | `404` |
| GET | `/api/staff/customers/{customerId}/statement?accountId=&from=&to=&page=1&size=20` | The customer's own statement — the same rows they see | `200` `PagedResult<StatementLineDTO>` | `400` bad range |
| GET | `/api/staff/accounts/{accountId}` | Account detail with the full account number. Writes an `ACCOUNT_VIEWED` audit row. | `200` | `404` |
| GET | `/api/staff/journal-entries/{idOrReference}` | A journal entry with **both** legs of every posting, by id or by the reference on the receipt (`KNX-TR-260922-0433`) | `200` | `404` |
| POST | `/api/staff/journal-entries/{journalEntryId}/reversals` | Undo an entry by posting its mirror image. Body: `{"reason": "..."}`, 10–200 characters. | `201` + `Location: /api/staff/journal-entries/{reversalId}` | `404`, `409` already reversed, `422` it is itself a reversal |
| GET | `/api/staff/events?actorType=&actorId=&action=&resourceType=&resourceId=&outcome=&from=&to=` | Business audit: who did what to which resource | `200` `PagedResult<AuditEventDTO>` | `400` unknown filter value, `403` not compliance |
| GET | `/api/staff/security-events?eventType=&subjectType=&subjectId=&from=&to=` | Sign-ups, OTP outcomes, logins, lockouts, token rotation, step-up issuance | `200` `PagedResult<SecurityEventDTO>` | `400`, `403` not compliance |

> **One write path, on purpose.** Staff cannot edit a balance, change a KYC level or close an account from here: those are the bank deciding something, and a decision goes through the flow that owns it. A posting made in error is different — the books cannot be edited at all, so the only way to put money back is to post its mirror image.

> **No `Idempotency-Key` on reversals.** The key is already in the database: `uq_journal_entry_reversal` allows one reversal per entry, so a retried request finds the money already back and gets a `409`. The header-based key would also need a customer to hang off, and the caller here is a member of staff.

> **The reason is customer-facing.** It becomes the reversal's description, which appears on the customer's statement. Write it for them, not for the ticket.

> **No second factor yet.** `staff_credential` has `totp_secret_ciphertext` and `mfa_enrolled_at` waiting. Until they are used, keep the staff console off the public internet.

> **There is no default administrator.** No migration seeds a staff account. The first one is created at startup from `app.identity.staff.bootstrap.*` when a username *and* a password are both configured and that username does not exist; it never overwrites an account that does.

---

## 9. Example: creating and confirming a transfer

**Create**

```http
POST /api/transfers
Authorization: Bearer eyJ...
Idempotency-Key: 7f3c1e0a-...
Content-Type: application/json

{
  "toAccountNumber": "100277814420",
  "amount": { "amount": "3500.00", "currency": "KES" },
  "note": "Cement, invoice 118"
}
```

```http
HTTP/1.1 201 Created
Location: /api/transfers/0192f7a4-...
Content-Type: application/json

{
  "id": "0192f7a4-...",
  "status": "PENDING_CONFIRMATION",
  "recipient": { "maskedName": "JOSEPH OT*****", "accountNumberMasked": "••••4420" },
  "amount": { "amount": "3500.00", "currency": "KES" },
  "fee": { "amount": "0.00", "currency": "KES" },
  "balanceAfter": { "amount": "21000.00", "currency": "KES" },
  "expiresAt": "2026-09-22T10:15:00Z"
}
```

**Step up with PIN**

```http
POST /api/step-up-tokens
Authorization: Bearer eyJ...
Content-Type: application/json

{ "intentType": "TRANSFER", "intentId": "0192f7a4-...", "pin": "••••" }
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "stepUpToken": "eyJ...", "expiresInSeconds": 120 }
```

**Confirm**

```http
POST /api/transfers/0192f7a4-.../confirmation
Authorization: Bearer eyJ...
Step-Up-Token: eyJ...
Idempotency-Key: 1b9d6bcd-...
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "0192f7a4-...",
  "status": "COMPLETED",
  "reference": "KNX-TR-260922-0433",
  "completedAt": "2026-09-22T10:05:12Z",
  "balanceAfter": { "amount": "21000.00", "currency": "KES" }
}
```

**Error example (ProblemDetail)**

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json

{
  "type": "https://api.konexio.example/problems/insufficient-funds",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "Amount KES 30,000.00 exceeds available balance KES 24,500.00.",
  "instance": "/api/transfers"
}
```

---

## 10. Summary

| Group | Endpoints |
|---|---|
| Auth Service | 10 |
| Customers and accounts | 7 |
| Money movement | 15 |
| Transactions | 3 |
| Loans | 6 |
| Provider callbacks | 5 |
| Staff console | 8 |
| **Total** | **54** |

> Fee amounts, limits, loan interest and due dates in examples are placeholders; replace them with your real policy values.
