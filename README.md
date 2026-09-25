# Konexio Online Bank

A retail bank as a modular monolith: sign-up and KYC, accounts, a double-entry ledger, deposits and withdrawals through payment providers, transfers between customers, transaction history and receipts, account closure, and short-term loans — with a customer-facing website and a staff/compliance API over the same core.

Java 25 · Spring Boot 4.1 · Spring Security 7 · Spring Modulith · PostgreSQL 18 · Thymeleaf · Flyway · Testcontainers

---

## Contents

- [Run it](#run-it)
- [Walkthrough](#walkthrough)
- [Architecture](#architecture)
- [Modules](#modules)
- [The ledger](#the-ledger)
- [Security](#security)
- [The website](#the-website)
- [REST API](#rest-api)
- [Background jobs](#background-jobs)
- [Database](#database)
- [Tests](#tests)
- [Configuration](#configuration)
- [Development notes](#development-notes)
- [Known gaps](#known-gaps)
- [Further documentation](#further-documentation)

---

## Run it

Requires JDK 25 and Docker.

```bash
./mvnw spring-boot:run
```

Spring Boot's Docker Compose support starts PostgreSQL 18 and MailHog from `docker-compose.yaml`, runs Flyway, and serves the site at <http://localhost:8080>. MailHog's inbox is at <http://localhost:8025>.

For local development, activate the `local` profile so the OTP appears in the log instead of an SMS:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Running from an IDE gives the same behaviour: devtools on the classpath is the signal `LoggingOtpSender` reads.

```
WARN  c.k.b.identity.domain.LoggingOtpSender : OTP for +254 7•• ••• 570 is 100423 (valid PT5M).
```

`requests.http` at the project root drives every endpoint from the IDE's HTTP client, chained so one run takes a customer from registration through borrowing.

To start with money already in an account and the reference data filled in:

```bash
docker exec -i bank-postgres-1 psql -U konexio -d konexio < scripts/dev-seed.sql
```

It posts an opening balance through the ledger — a balanced entry, not an `UPDATE` of a balance — and seeds the transaction limits and fee rules that are otherwise left unset. Safe to run twice; every statement is guarded.

---

## Walkthrough

Every step, twice: what a customer taps, and the call underneath. `$TOKEN` is the `accessToken` from step 2.

### 1. Register

**Web** `/register` → details → contact → verify → PIN → review → `/register/done`, which opens the first account and hands over to `/login`.

**API** Start the sign-up:

```bash
curl -X POST http://localhost:8080/api/registrations \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Joseph Otieno","nationalId":"31234570","dateOfBirth":"1994-04-12",
       "phone":"+254712345570","email":"joseph@example.com"}'
```

Returns `201` with the registration `id`. The OTP is sent by SMS in production and printed to the log in development:

```
WARN  LoggingOtpSender : OTP for +254 7•• ••• 570 is 100423 (valid PT5M).
```

Verify it, then set the PIN — which completes the sign-up and creates the credential:

```bash
curl -X POST http://localhost:8080/api/registrations/$REG_ID/otp-verification \
  -H "Content-Type: application/json" -d '{"code":"100423"}'

curl -X PUT http://localhost:8080/api/registrations/$REG_ID/pin \
  -H "Content-Type: application/json" -d '{"pin":"2483"}'
```

`POST /api/registrations/$REG_ID/otp-resends` sends another code.

### 2. Log in

**Web** `POST /login` with the phone (no country code — the form prefixes it) and PIN. The site keeps a session cookie.

**API** `POST /api/tokens` returns a bearer token pair:

```bash
curl -X POST http://localhost:8080/api/tokens \
  -H "Content-Type: application/json" \
  -d '{"grantType":"pin","phone":"+254712345570","pin":"2483",
       "deviceId":"cli-laptop-01","platform":"WEB","deviceModel":"curl"}'
```

`deviceId` identifies the device the refresh token is bound to and must be 8–128 characters; a shorter one is refused with `422`.

`grantType` is `pin` or `refresh_token` — not `password`. Logging out is `POST /api/tokens/revocation` with the refresh token, which kills the whole token family.

### 3. Open an account

**Web** Done automatically at the end of sign-up; `/accounts` lists them afterwards.

**API**

```bash
curl -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" -d '{"type":"MAIN","nickname":"Everyday"}'
```

`MAIN` or `SAVINGS`. Returns `201` with the twelve-digit `accountNumber`.

### 4. Deposit

**Web** `/deposits` → source → amount → review → PIN → done.

**API** Three calls, because a deposit is a request before it is money. Create the intent:

```bash
curl -X POST http://localhost:8080/api/deposits \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"channel":"MPESA","amount":"10000.00","msisdn":"+254712345570"}'
```

Re-enter the PIN for this specific intent, then confirm with the token it returns:

```bash
curl -X POST http://localhost:8080/api/step-up-tokens \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"intentType":"DEPOSIT","intentId":"'$DEPOSIT_ID'","pin":"2483"}'

curl -X POST http://localhost:8080/api/deposits/$DEPOSIT_ID/confirmation \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H "Step-Up-Token: $STEP_UP"
```

That returns `202 PROCESSING` and **credits nothing**: the bank has asked the provider for the money, not received it. The account is credited when the provider calls back — see [Development notes](#development-notes) for how to send that yourself locally.

### 5. Transfer

**Web** `/transfers` → account number → name check → amount → review → PIN → receipt.

**API** Check who owns the number first — this is the name the customer confirms:

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/recipients/100200000014
```

Then create, step up with `"intentType":"TRANSFER"`, and confirm exactly as in step 4. A transfer confirms to `200 COMPLETED`: both legs are inside this bank, so the money moves in one ledger entry with no provider to wait for.

### 6. Withdraw

**Web** `/withdrawals` → destination → amount → review → PIN → done.

**API** As step 4 with `POST /api/withdrawals` and `"intentType":"WITHDRAWAL"`. Confirming returns `202`: the account is debited immediately, and the payout is instructed — so the balance is right away true, while arrival at the other end waits on the callback.

### 7. History and receipts

**Web** `/activity`, filtered with `?filter=in|out|loans`; a row opens its receipt.

**API**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/transactions?direction=IN&page=1&size=20"

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/transactions/$TX_ID
curl -H "Authorization: Bearer $TOKEN" -H "Accept: application/pdf" \
  http://localhost:8080/api/transactions/$TX_ID/receipt --output receipt.pdf
```

### 8. Borrow

**Web** `/loans` → terms → PIN → disbursed → loan account.

**API** Reading the offers prices one and stores it, so the same call twice returns the same offer:

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/loan-offers
```

Step up with `"intentType":"LOAN_ACCEPTANCE"` and the **offer** id, then accept:

```bash
curl -X POST http://localhost:8080/api/loans \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H "Step-Up-Token: $STEP_UP" -H "Content-Type: application/json" \
  -d '{"offerId":"'$OFFER_ID'","disburseToAccountId":"'$ACCOUNT_ID'","termsAccepted":true}'
```

One transaction opens the loan account, pays the principal into the customer's account and writes the repayment schedule. `termsAccepted` must be true — the module refuses rather than assuming.

### 9. Close an account

**Web** `/accounts/{id}` → "Close this account" → the three checks → warning and consent → PIN → closed.

**API** Ask what stands in the way; it is a checklist, not a refusal, so it always answers `200`:

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/accounts/$ACCOUNT_ID/closure-eligibility
```

All three must pass — dormant, empty, no loan against it. Then step up with `"intentType":"ACCOUNT_CLOSURE"` and:

```bash
curl -X DELETE "http://localhost:8080/api/accounts/$ACCOUNT_ID?reason=NOT_USED" \
  -H "Authorization: Bearer $TOKEN" -H "Step-Up-Token: $STEP_UP"
```

A logical delete: the record stays with a closure reference on it.

### 10. Staff

Set `STAFF_BOOTSTRAP_USERNAME` and `STAFF_BOOTSTRAP_PASSWORD` before boot to create the first account, then:

```bash
curl -X POST http://localhost:8080/api/staff/tokens \
  -H "Content-Type: application/json" -d '{"username":"...","password":"..."}'

curl -H "Authorization: Bearer $STAFF_TOKEN" \
  "http://localhost:8080/api/staff/customers?q=Joseph"
```

From there: the customer dossier, their statement, an account, a journal entry by id or reference, a reversal, and the audit and security event logs.

---

## Architecture

One deployable, one database, thirteen modules enforced by [Spring Modulith](https://spring.io/projects/spring-modulith). Each module owns a PostgreSQL schema and exposes a narrow Java API; everything else in it is internal and `ModularityTests` fails the build if another module reaches past it.

```
shared → identity → customer + account → ledger → api_security → payment
       → transactions → loan → jobs / staff / notification / site
```

A module's public surface is the root package — `AccountApi`, `PaymentApi`, `LoanApi`, `TransactionsApi` — plus the view records those return. Domain types live in `*.domain` and never leave.

Modules talk in two ways: a direct call through another module's API for something needed now (a payment asks the ledger to post), and an application event for something that follows (a closed account, a completed payment) which the notification module picks up through an outbox.

Events are delivered synchronously today: listeners are plain `@EventListener`s, so `DomainEventRecorder` writes each outbox row inside the transaction that published the event, and `OutboxRelay` sends it later on a timer. `V19` creates Spring Modulith's `event_publication` table ahead of a move to after-commit `@ApplicationModuleListener`s, but nothing uses it yet.

---

## Modules

| Module | Schema | What it owns |
|---|---|---|
| `shared` | `common`, `audit` | `Money`, `PagedResult`, problem-detail handling, actor context, SQLSTATE→HTTP mapping, masking, the append-only audit log |
| `identity` | `identity` | Registration, OTP, PIN, login, refresh, JWKS, step-up tokens, staff credentials, security events |
| `customer` | `customer` | The customer profile behind a credential |
| `account` | `account` | Opening, listing, dormancy, and the closure checklist |
| `ledger` | `ledger` | Double-entry journal, postings, balances, the customer statement view |
| `apisecurity` | `api_security` | Idempotency keys, step-up verification, ownership guards |
| `payment` | `payment` | Deposits, withdrawals, transfers, fees, limits, provider callbacks |
| `transactions` | — | Read model over the ledger: history, receipts, PDF |
| `loan` | `loan` | Products, pricing, offers, acceptance, disbursement, repayment schedule |
| `notification` | `outbox` | Outbox of domain events, email and SMS delivery |
| `jobs` | `jobs` | Scheduled work, locked across instances on a ShedLock-compatible table |
| `staff` | — | Customer search, dossiers, statements, journal reversals, audit and security event queries |
| `site` | — | The customer-facing website |

---

## The ledger

Every movement of money is a journal entry with balanced postings. Nothing debits or credits an account by updating a number.

- **Balances are derived, not set.** A trigger maintains `account.ledger_balance` from postings, so a balance cannot drift from the entries behind it.
- **Entries are immutable.** A mistake is corrected with a reversal entry, through `POST /api/staff/journal-entries/{id}/reversals`, which requires a written reason.
- **Customers see a view, not the tables.** `ledger.v_customer_statement` is where "what a customer may see of the books" is defined: internal GL accounts and loan accounts are excluded, and debit/credit becomes money out/money in.
- **A deposit posts on arrival, not on request.** Confirming a deposit asks the provider to collect; the entry is posted when the provider's callback says the money arrived. See [Development notes](#development-notes).
- **Internal accounts** (M-Pesa clearing, card clearing, agent clearing, fee income, interest income) are seeded by `V17` and are the second leg of every posting that faces the outside world.

### Lock order

`ledger.apply_posting()` locks each account a posting touches. So that two entries touching the same accounts can never deadlock, every writer inserts an entry's lines in ascending account-id order: `PostEntryCommand` sorts them with `PostingLine.LOCK_ORDER`. The comparison is unsigned, to match PostgreSQL's ordering of `uuid` rather than Java's signed `UUID.compareTo`.

---

## Security

- **Customer auth** is a JWT access token with a refresh token, minted by `identity` and signed with a rotating RSA key. The resource-server filter chain decodes in-process; `/.well-known/jwks.json` exists for external clients.
- **Step-up tokens** re-authorise a single action. Issued for one `intentType` and one `intentId`, verified and burned inside the same transaction that moves the money, so a token minted for one payment cannot confirm another and a failed confirmation does not spend the customer's PIN entry.
- **Idempotency** is required on the paths in `app.api-security.idempotency.paths` — account opening, the three payment creates, their confirmations, and loan acceptance. A reused key with a different body is refused.
- **Ownership** is checked in the module that owns the resource. Somebody else's account, payment or receipt is a `404`, never a `403`: an id cannot be probed for existence.
- **Rate limiting** covers login and the recipient lookup, which is the endpoint that turns an account number into a name.
- **Audit** is append-only in `audit`, and records refusals as well as successes — a rejected closure is exactly the thing a customer later says they never attempted.
- **Masking** is applied wherever a name, account number, phone or national ID is shown back: `Joseph Ot****`, `••••4420`.

The staff API is a separate filter chain with its own token, roles and TTL.

---

## The website

Server-rendered Thymeleaf over the same modules the API uses, session-authenticated, with progressive enhancement: every page works without JavaScript, and the scripts only add the PIN keypad, amount grouping, one-tap amounts, balance hiding, copy and share.

It covers sign-up, login and home; deposits, withdrawals and transfers; activity history and receipts; accounts and account closure; and borrowing.

Each flow is a session-scoped form carried from one step to the next, with a guard on every one: reaching a PIN step without having passed the step before it redirects back rather than proceeding.

---

## REST API

Conventions, status codes, headers and the full endpoint list are in [`docs/rest-api.md`](docs/rest-api.md). In short:

- Plural nouns under `/api`; `POST` returns `201` with a `Location`; lists are 1-based `page`/`size` returning `PagedResult`; errors are RFC 9457 problem details.
- Money is `{"amount": "3500.00", "currency": "KES"}` — a string, so no precision is lost to a JSON number.
- IDs are UUIDv7, generated by PostgreSQL 18.

### Error mapping

Constraints in the database are the last line of defence for business rules, so a violation that reaches the web layer is still a proper problem detail. `GlobalExceptionHandler` maps the SQLSTATE:

| SQLSTATE | Meaning | HTTP | Problem type |
|---|---|---|---|
| `23514` | check constraint | `422` | `business-rule-violation` |
| `23505` | unique violation | `409` | `duplicate-resource` |
| `23P01` | exclusion (overlapping period) | `409` | `overlapping-period` |
| `23503` | foreign key | `422` | `missing-reference` |
| `42501` | insufficient privilege | `500` | `internal-error`, logged as a bug: the app tried to write something its role is not granted, such as a ledger or audit row or `account.ledger_balance` |
| anything else | | `500` | `internal-error` |

An optimistic-locking failure is a `409` `concurrent-modification`.

| Area | Endpoints |
|---|---|
| Registration | `POST /api/registrations`, OTP resend and verification, `PUT .../pin` |
| Tokens | `POST /api/tokens` (`pin`, `refresh_token`), `/revocation`, `POST /api/staff/tokens` |
| Step-up | `POST /api/step-up-tokens` |
| Accounts | open, list, get, closure eligibility, `DELETE /{id}?reason=` |
| Payments | deposits, withdrawals, transfers — create, patch, confirm, get, cancel |
| Callbacks | M-Pesa STK, B2C result and timeout, card charges, agent transactions |
| Transactions | history with filters, receipt as JSON, receipt as PDF |
| Loans | current offers, one offer, accept, list, get, repayment schedule |
| Staff | customer search, dossier, statement, account, journal entry, reversal, audit and security events |

---

## Background jobs

Scheduled in `jobs` and disabled with `app.jobs.enabled=false`. Only one instance runs each job: `JobLock` takes a row in `jobs.shedlock` with a single `INSERT … ON CONFLICT … WHERE lock_until <= now()`, using database time so instance clocks don't matter. It is a hand-written implementation of ShedLock's table contract rather than the library, so adopting ShedLock later is a configuration change, not a migration. Each cron is overridable with `app.jobs.cron.<job>`.

| Job | Default | What it does |
|---|---|---|
| Dormancy scan | `0 15 2 * * *` | Marks accounts dormant after the configured period |
| Intent expiry | every 5 min | Retires quotes nobody confirmed |
| Payment reconciliation | every 2 min | Reprocesses stored callbacks; reports intents stuck `PROCESSING` |
| Loan reminders | `0 0 9 * * *` | Tells customers a loan falls due |
| CRB reporting | `0 30 23 * * *` | Reports loans to the credit bureau |
| Housekeeping | `0 45 3 * * *` | Expires idempotency keys and old rows |

---

## Database

One database, one Flyway history, a schema per module. Migrations live flat in `src/main/resources/db/migration` and run in version order:

| Version | Contents |
|---|---|
| `V1` | `common` schema: `updated_at` and no-modification triggers, Luhn check digit, actor-context functions |
| `V2`–`V5` | `identity`: credentials and devices, registration and OTP, refresh/step-up tokens and signing keys, security events |
| `V6` | `customer` profile |
| `V7`–`V8` | `account`: accounts, account numbers, status history, closure reference |
| `V9`–`V10` | `ledger`: journal, postings, `apply_posting()`, balance check, customer statement view, journal references |
| `V11` | `payment`: intents, enforced status transitions, status history, provider callbacks, fee rules, transaction limits |
| `V12` | `loan`: products, pricing, offers, loans, repayment schedule and repayments, CRB submissions |
| `V13` | `api_security`: idempotency keys |
| `V14` | `outbox`: domain events and per-channel deliveries |
| `V15` | `audit`: append-only audit log |
| `V16` | `jobs`: lock table and run history; empty `batch` schema |
| `V17` | Reference data: internal GL accounts and the loan product (fee rules and limits are left unseeded) |
| `V18` | Indexes for staff customer search (trigram on name, email) |
| `V19` | Spring Modulith `public.event_publication` table |
| `V20` | Loan pricing for `INSTANT_10K`: 1% flat interest, no processing fee |
| `R__konexio_grants` | Least-privilege grants for the application role |

Two roles: `konexio_migrator` owns the schema, `konexio_app` runs the application with least privilege, granted by a repeatable migration that re-runs on every migrate. Local Docker Compose creates both from `src/main/resources/db/init/roles.sql`.

Two roles: `konexio_migrator` owns the schema, `konexio_app` runs the application with least privilege, granted by a repeatable migration that re-runs on every migrate.

The next change goes in the next free version, e.g. `V21__loan_add_early_repayment.sql`. Applied files are never edited.

---

## Tests

```bash
./mvnw verify
```

49 integration test classes (`*IT`, run by Failsafe in `verify`, need Docker) and 9 unit test classes (`*Test`/`*Tests`, run by Surefire in `test`, need only a JVM). Integration tests run against real PostgreSQL 18 through Testcontainers, the same image as production, and exercise HTTP endpoints end to end rather than mocking the layer below.

- `ModularityTests` verifies the module boundaries and fails the build when one is crossed.
- Site tests drive the real screens through MockMvc with a session, asserting rendered HTML and — on any screen that moves money — the resulting ledger balances.
- Payment tests drive provider callbacks themselves, which is most of what they are for.

---

## Configuration

Everything is an environment variable with a safe default in `application.properties`.

| Variable | Default | Purpose |
|---|---|---|
| `PAYMENT_PROVIDER` | `stub` | Which payment provider client to wire |
| `CREDIT_REFERENCE` | `stub` | Credit bureau client |
| `IDEMPOTENCY_ENABLED` | `true` | Idempotency filter |
| `CALLBACK_SECRET` | *(empty)* | HMAC secret for provider callbacks; empty means signatures are not required |
| `CALLBACK_ALLOWED_IPS` | *(empty)* | Callback source allow-list |
| `OTP_LOG_CODES` | `false` | Print OTPs to the log — development only |
| `STAFF_BOOTSTRAP_USERNAME` / `_PASSWORD` | *(empty)* | Creates the first staff account on boot; does nothing unless both are set, and never overwrites an existing one |

The `local` profile (`application-local.properties`) turns on what local development needs. Everything in it weakens something on purpose, which is why it is a profile and not a default.

---

## Development notes

**Deposits need a provider callback.** Confirming a deposit asks the provider for money and marks the intent `PROCESSING`; the account is credited when the provider calls back. The default `StubPaymentProvider` accepts every instruction and reports nothing back — which is exactly what a real provider looks like from this side — so on a laptop a deposit stays `PROCESSING` until you send the callback yourself:

```bash
curl -X POST http://localhost:8080/api/callbacks/mpesa/stk-results \
  -H "Content-Type: application/json" \
  -d '{"externalReference":"ws_CO_...","successful":true,"resultCode":"0","resultDescription":"Success"}'
```

The endpoint must match the channel — `mpesa/stk-results`, `card-charges` or `agent-transactions` — because the callback is matched on `(channel, externalReference)`. The reference is in the log line the stub prints when it accepts the collection. `requests.http` chains this automatically.

**Fees and limits are deliberately unseeded.** Fee rules and transaction limits are decisions somebody signs off, not defaults. Until rows exist:

- every fee is `KES 0.00` and the amount screens state no limits,
- and the app logs `No transaction_limit configured for … — payments are running unlimited`.

Loan pricing is the exception: `V20` approves 1% flat interest with no processing fee on `INSTANT_10K`, so `/loans` has an offer on a fresh database — KES 10,000 borrowed, KES 10,100 repaid after 30 days.

---

## Known gaps

Honest list of what is not finished.

- **`/forgot-pin` and `/loan-terms`** are linked from the PIN screens and the loan terms screen but have no route yet.
- **"Download statements"** on the closure warning links to the activity list; there is no per-account statement PDF, only the per-transaction receipt.
- **Transfer rows in history** read `Transfer: <note>` rather than "Sent to JOSEPH OT*****". A journal entry has one description shared by both sides, so naming the counterparty per side needs a per-posting description in the ledger — a change to the books, not to a screen.
- **Withdrawal rows** render the raw channel, `Withdrawal to MPESA` rather than `M-Pesa`.
- **Money formatting** is grouped on the transfer, activity, receipt, accounts and loan screens, but the deposit and withdrawal screens still print `Money.toString()`, which does not group thousands.
- **No per-account statement export**, and no repayment endpoint for loans: a loan is disbursed and tracked, but repayment is not yet a customer-facing flow.

---

## Further documentation

| Document | Contents |
|---|---|
| [`docs/rest-api.md`](docs/rest-api.md) | API conventions and the full endpoint contract |
| [`docs/architecture-c4model.dsl`](docs/architecture-c4model.dsl) | C4 model of the system |
| [`docs/banking-flow.excalidraw`](docs/banking-flow.excalidraw) | Flow diagram of sign-up and the six banking steps, with the ledger postings each makes; open at [excalidraw.com](https://excalidraw.com) or in the IDE's Excalidraw plugin |
| [`requests.http`](requests.http) | Every endpoint, chained, for the IDE HTTP client |
