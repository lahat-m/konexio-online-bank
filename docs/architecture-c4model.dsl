/*
 * Konexio Online Bank - C4 model (single Structurizr DSL file)
 *
 *   L1 System Context : SystemContext
 *   L2 Containers     : Containers
 *   L3 Components     : Components-AuthService, Components-BankingApi,
 *                       Components-BankingApi-Security, Components-Worker
 *   L4 Code           : Code-AuthJwt, Code-ResourceServer, Code-TransferLedger,
 *                       Code-AccountClosure, Code-LoanDisbursement
 *   Dynamic           : Dynamic-Login, Dynamic-Deposit, Dynamic-Transfer,
 *                       Dynamic-CloseAccount, Dynamic-LoanDisbursement
 *
 * L4 note: the DSL has no "code" element type, so classes are modelled as
 * components tagged "Code" (grouped by Java package). L3 views exclude them;
 * L4 views include them by tag.
 *
 * Stack: Java 25 | Spring Boot 4 | Spring Security 7 (custom JWT) | PostgreSQL 18
 * Run:   docker run -it --rm -p 8080:8080 -v "${PWD}:/usr/local/structurizr" structurizr/structurizr local
 */
workspace "Konexio Online Bank" "Digital bank account: open account, deposit, withdraw, transfer, close dormant account, KES 10,000 loan." {

    !identifiers hierarchical

    model {

        # ==================================================================
        # PEOPLE & BOUNDARIES
        # ==================================================================
        customer = person "Customer" "Kenyan retail or SME customer. Uses the mobile app to bank and borrow."

        group "Konexio Bank (enterprise boundary)" {

            opsStaff = person "Operations Staff" "Resolves support cases, failed payments and reversals."
            compliance = person "Compliance Officer" "Audits KYC, dormant accounts, closures and security events."

            konexio = softwareSystem "Konexio Online Bank" "Opens accounts, moves money (M-Pesa, card, agent, internal), closes dormant accounts, disburses loans." {

                # ----------------------------------------------------------
                group "Channels" {
                    mobileApp = container "Customer Mobile App" "Onboarding, balance, deposit, withdraw, transfer, history, account closure, loans. Tokens kept in OS keystore." "React Native, TypeScript" "Mobile App"
                    backOffice = container "Back-office Web App" "Staff portal: account review, reversals, KYC and audit trails." "React SPA, TypeScript" "Web Browser"
                }

                # ----------------------------------------------------------
                group "Services" {

                    # ======================== AUTH SERVICE ========================
                    authService = container "Auth Service" "Identity provider: sign-up, OTP, KYC, phone + PIN login. Issues RS256 JWTs (access, step-up) and rotating refresh tokens; publishes JWKS." "Java 25, Spring Boot 4, Spring Security 7" {

                        group "API layer - REST endpoints" {
                            authController = component "Auth Controller" "/auth/register, /otp/verify, /pin, /login, /refresh, /logout." "Spring MVC @RestController"
                            stepUpController = component "Step-up Controller" "/auth/step-up: re-checks PIN for one payment intent; returns 2-min single-use token." "Spring MVC @RestController"
                            jwksController = component "JWKS Endpoint" "/.well-known/jwks.json: current + previous RSA public keys by kid." "Spring MVC @RestController"
                        }

                        group "Security layer - Spring Security 7" {
                            authSecurityConfig = component "Auth Filter Chain" "Lambda DSL; stateless; permits /auth/** + JWKS; rate-limit filter; PathPatternRequestMatcher." "SecurityFilterChain" "Security"
                            pinAuthProvider = component "Phone + PIN Auth Provider" "Custom AuthenticationProvider: lockout check, credential lookup, Argon2id PIN verify." "AuthenticationProvider" "Security"
                            pinEncoder = component "PIN Encoder" "Argon2id hash with salt + server pepper; never stores plain PIN." "Argon2Password4jPasswordEncoder" "Security"
                            tokenService = component "JWT Token Service" "Mints access (10 min) and step-up (2 min) JWTs; rotates opaque refresh tokens (30 d) with reuse detection." "NimbusJwtEncoder" "Security"
                            keyProvider = component "Signing Key Provider" "RSA key pairs from Vault; JWKSource with kid rotation." "Nimbus JWKSource" "Security"
                            loginAttemptGuard = component "Login Attempt Guard" "Locks credential after 5 bad PINs for 30 min; throttles per device and IP." "Spring @Component" "Security"
                        }

                        group "Domain layer - onboarding" {
                            registrationService = component "Registration Service" "Sign-up orchestration: KYC → OTP → PIN → credential." "Spring @Service"
                            otpService = component "OTP Service" "6-digit OTP, hashed, 5-min expiry, max 3 attempts, resend throttle." "Spring @Service"
                        }

                        group "Integration layer - outbound clients" {
                            kycClient = component "KYC Client" "Matches National ID, name and DOB via provider API." "Spring RestClient"
                            smsClient = component "SMS Client" "Sends OTP SMS." "Spring RestClient"
                        }

                        group "Persistence layer - Spring Data JDBC" {
                            credentialRepo = component "Credential Repository" "customer_credential / staff_credential: PIN hash, status, failed_attempts, locked_until." "Spring Data JDBC"
                            otpRepo = component "OTP Repository" "otp_challenge: code hash, purpose, expires_at, attempts." "Spring Data JDBC"
                            refreshTokenRepo = component "Refresh Token Repository" "refresh_token: SHA-256 hash, family_id, device_id, expires_at, revoked_at." "Spring Data JDBC"
                        }

                        # ------------------- L4 CODE (Auth) -------------------
                        group "Code: com.konexio.auth.config" {
                            clsAuthSecurityConfig = component "AuthSecurityConfig" "@Configuration. Beans: SecurityFilterChain, AuthenticationManager, PIN PasswordEncoder, JwtEncoder." "Java class" "Code,Code-AuthJwt"
                        }
                        group "Code: com.konexio.auth.security" {
                            clsPhonePinToken = component "PhonePinAuthenticationToken" "Before auth: phone, PIN, deviceId. After auth: CustomerPrincipal + authorities; PIN erased." "Java class" "Code,Code-AuthJwt"
                            clsPhonePinProvider = component "PhonePinAuthenticationProvider" "authenticate(): lockout → load credential → Argon2 matches() → authenticated token." "Java class" "Code,Code-AuthJwt"
                            clsLoginAttemptGuard = component "LoginAttemptGuard" "assertNotLocked(), recordFailure(), recordSuccess()." "Java class" "Code,Code-AuthJwt"
                            clsCustomerPrincipal = component "CustomerPrincipal" "customerId (uuidv7), phone, roles, kycLevel." "Java record" "Code,Code-AuthJwt"
                        }
                        group "Code: com.konexio.auth.token" {
                            clsJwtTokenService = component "JwtTokenService" "issue(), refresh(), issueStepUp(intent), revokeFamily()." "Java class" "Code,Code-AuthJwt"
                            clsSigningKeyProvider = component "SigningKeyProvider" "Active + previous RSA keys by kid; jwkSource(), publicJwkSet()." "Java class" "Code,Code-AuthJwt"
                            clsTokenPair = component "TokenPair" "accessToken, refreshToken, expiresInSeconds." "Java record" "Code,Code-AuthJwt"
                        }
                        group "Code: com.konexio.auth.persistence" {
                            clsCredentialRepo = component "CustomerCredentialRepository" "findByPhone(), updateFailedAttempts()." "Java interface" "Code,Code-AuthJwt"
                            clsRefreshTokenRepo = component "RefreshTokenRepository" "findByTokenHash(), save(), revokeFamily()." "Java interface" "Code,Code-AuthJwt"
                        }
                        group "Code: Spring Security 7 (framework)" {
                            fwAuthenticationProvider = component "AuthenticationProvider" "SPI for custom authentication." "Java interface" "Code,Framework,Code-AuthJwt"
                            fwArgon2Encoder = component "Argon2Password4jPasswordEncoder" "Argon2id PasswordEncoder (added in 7.0)." "Java class" "Code,Framework,Code-AuthJwt"
                            fwNimbusJwtEncoder = component "NimbusJwtEncoder" "Signs JwtClaimsSet + JwsHeader(RS256, kid)." "Java class" "Code,Framework,Code-AuthJwt"
                        }
                    }

                    # ===================== CORE BANKING API =======================
                    bankingApi = container "Core Banking API" "Accounts, double-entry ledger, payments, dormant closure, loans. OAuth2 resource server trusting Auth Service JWTs." "Java 25, Spring Boot 4, Spring Security 7" {

                        group "API layer - REST endpoints" {
                            accountController = component "Account Controller" "POST /accounts, GET /accounts/{id}, GET /accounts/{id}/closure-eligibility, DELETE /accounts/{id}." "Spring MVC @RestController"
                            paymentController = component "Payment Controller" "POST /payment-intents (deposit|withdrawal|transfer), POST /{id}/confirm, GET /recipients/{accountNo}." "Spring MVC @RestController"
                            historyController = component "History Controller" "GET /transactions, GET /transactions/{id}/receipt." "Spring MVC @RestController"
                            loanController = component "Loan Controller" "GET /loan-offers, POST /loans (accept + disburse), GET /loans/{id}." "Spring MVC @RestController"
                            callbackController = component "Callback Controller" "POST /callbacks/mpesa|card|agent: async payment results." "Spring MVC @RestController"
                        }

                        group "Security layer - Spring Security 7" {
                            apiSecurityConfig = component "API Filter Chains" "Chain 1 /callbacks/**: IP allowlist + HMAC. Chain 2 API: JWT resource server, stateless, AuthorizationManager rules." "SecurityFilterChain" "Security"
                            jwtDecoder = component "JWT Decoder" "Verifies RS256 via cached JWKS; checks iss, aud, exp, nbf, typ." "NimbusJwtDecoder" "Security"
                            jwtAuthConverter = component "JWT Authentication Converter" "Claims (sub, roles, kyc_level, device_id) → authorities + KonexioPrincipal." "Converter<Jwt, AbstractAuthenticationToken>" "Security"
                            ownershipAuthz = component "Account Ownership Guard" "Allows /accounts/{accountId}/** only for the account owner." "AuthorizationManager" "Security"
                            stepUpVerifier = component "Step-up Verifier" "Money moves only with step-up JWT matching intent_id + amount; jti single-use." "Spring @Component" "Security"
                            idempotencyFilter = component "Idempotency Filter" "Requires Idempotency-Key on money writes; replays stored response on retry." "OncePerRequestFilter" "Security"
                        }

                        group "Domain layer - banking rules" {
                            accountService = component "Account Service" "Opens main/savings/loan accounts; logical close (status CLOSED, ledger kept)." "Spring @Service"
                            closurePolicy = component "Closure Eligibility Policy" "3 checks: dormant, balance KES 0.00, no active loan. Drives UI checklist." "Spring @Component"
                            paymentService = component "Payment Service" "Intent lifecycle; limits, fee, balance checks; routes to channel or ledger." "Spring @Service"
                            ledgerService = component "Ledger Service" "Double-entry posting in one transaction; row locks; debits = credits." "Spring @Service"
                            loanService = component "Loan Service" "Offer, eligibility, loan account, KES 10,000 disbursement, schedule." "Spring @Service"
                            outboxPublisher = component "Outbox Publisher" "Writes events in the same DB transaction as the posting (no lost alerts)." "Spring @Component"
                        }

                        group "Integration layer - outbound clients" {
                            mpesaClient = component "M-Pesa Client" "STK Push (deposit), B2C (withdrawal), Daraja OAuth token cache." "Spring RestClient"
                            cardClient = component "Card Gateway Client" "Card charge + 3-D Secure session." "Spring RestClient"
                            crbClient = component "CRB Client" "Credit score lookup for loan eligibility." "Spring RestClient"
                        }

                        group "Persistence layer - Spring Data JDBC" {
                            accountRepo = component "Account Repository" "account: owner, type, status, ledger_balance, last_activity; FOR UPDATE locks." "Spring Data JDBC"
                            intentRepo = component "Payment Intent Repository" "payment_intent, idempotency_key, used_step_up_jti." "Spring Data JDBC"
                            ledgerRepo = component "Ledger Repository" "journal_entry + posting (append-only)." "Spring Data JDBC"
                            loanRepo = component "Loan Repository" "loan + repayment_schedule." "Spring Data JDBC"
                            outboxRepo = component "Outbox Repository" "outbox_event: type, payload JSONB, status." "Spring Data JDBC"
                        }

                        # ------------- L4 CODE (Resource server security) -------------
                        group "Code: com.konexio.banking.security" {
                            clsBankingSecurityConfig = component "BankingSecurityConfig" "@Configuration. @Order(1) callback chain; @Order(2) API chain with oauth2ResourceServer(jwt)." "Java class" "Code,Code-ResourceServer"
                            clsTokenTypeValidator = component "TokenTypeValidator" "OAuth2TokenValidator<Jwt>: rejects wrong typ (access vs step_up)." "Java class" "Code,Code-ResourceServer"
                            clsJwtConverter = component "KonexioJwtAuthenticationConverter" "roles → ROLE_*, kyc_level → KYC_*; principal from sub." "Java class" "Code,Code-ResourceServer"
                            clsKonexioPrincipal = component "KonexioPrincipal" "customerId, deviceId, kycLevel." "Java record" "Code,Code-ResourceServer"
                            clsOwnershipManager = component "AccountOwnershipAuthorizationManager" "authorize(): {accountId}.ownerId == principal.customerId." "Java class" "Code,Code-ResourceServer"
                            clsStepUpVerifier = component "StepUpTokenVerifier" "verify(token, principal, intent): typ, amr=pin, sub, intent_id, amount, exp, unused jti." "Java class" "Code,Code-ResourceServer,Code-TransferLedger,Code-AccountClosure,Code-LoanDisbursement"
                            clsIdempotencyFilter = component "IdempotencyKeyFilter" "Stores key + response hash; 409 on key reuse with different body." "Java class" "Code,Code-ResourceServer"
                            clsCallbackFilter = component "CallbackSignatureFilter" "Verifies provider IP + HMAC-SHA256 signature." "Java class" "Code,Code-ResourceServer"
                        }
                        group "Code: Spring Security 7 (framework)" {
                            fwNimbusJwtDecoder = component "NimbusJwtDecoder" "withJwkSetUri(...).build(); runs OAuth2TokenValidators." "Java class" "Code,Framework,Code-ResourceServer"
                            fwAuthorizationManager = component "AuthorizationManager" "SPI: authorize() → AuthorizationResult (check() removed in 7.0)." "Java interface" "Code,Framework,Code-ResourceServer"
                        }

                        # ------------- L4 CODE (Payments, ledger, accounts, loans) -------------
                        group "Code: com.konexio.banking.payment" {
                            clsPaymentController = component "PaymentController" "create(), confirm(stepUpToken, Idempotency-Key), lookupRecipient()." "Java class" "Code,Code-TransferLedger"
                            clsPaymentService = component "PaymentService" "createIntent(), confirm(), complete(), fail()." "Java class" "Code,Code-TransferLedger"
                            clsPaymentIntent = component "PaymentIntent" "type, status (PENDING_CONFIRMATION→PROCESSING→COMPLETED|FAILED|EXPIRED), amount, fee." "Java class (entity)" "Code,Code-TransferLedger"
                            clsIntentRepo = component "PaymentIntentRepository" "save(), findByIdAndOwnerId()." "Java interface" "Code,Code-TransferLedger"
                        }
                        group "Code: com.konexio.banking.ledger" {
                            clsLedgerService = component "LedgerService" "@Transactional post(JournalRequest): lock → overdraft check → balance check → append." "Java class" "Code,Code-TransferLedger,Code-LoanDisbursement"
                            clsJournalEntry = component "JournalEntry" "intentId, postedAt, postings; assertBalanced()." "Java class (entity)" "Code,Code-TransferLedger"
                            clsPosting = component "Posting" "accountId, DEBIT|CREDIT, amount, balanceAfter. Append-only." "Java class (entity)" "Code,Code-TransferLedger"
                            clsMoney = component "Money" "BigDecimal NUMERIC(19,2) + currency KES; plus(), minus()." "Java record" "Code,Code-TransferLedger,Code-LoanDisbursement"
                            clsLedgerRepo = component "LedgerRepository" "save(JournalEntry), findByAccount()." "Java interface" "Code,Code-TransferLedger"
                        }
                        group "Code: com.konexio.banking.account" {
                            clsAccountController = component "AccountController" "closureEligibility() → checklist; close() → 409 failing checks | 200 receipt." "Java class" "Code,Code-AccountClosure"
                            clsAccountService = component "AccountService" "open(), openLoanAccount(), close(reason)." "Java class" "Code,Code-AccountClosure,Code-LoanDisbursement"
                            clsAccount = component "Account" "accountNumber, ownerId, status (ACTIVE|DORMANT|CLOSED), ledgerBalance, lastActivityAt, closedAt." "Java class (entity)" "Code,Code-TransferLedger,Code-AccountClosure"
                            clsAccountRepo = component "AccountRepository" "lockAllForUpdate(sortedIds), findOwnerId()." "Java interface" "Code,Code-TransferLedger,Code-AccountClosure,Code-ResourceServer"
                            clsClosurePolicy = component "ClosureEligibilityPolicy" "evaluate(account): runs all ClosureChecks." "Java class" "Code,Code-AccountClosure"
                            clsClosureCheck = component "ClosureCheck" "code(), check(account) → CheckResult{passed, message, fixAction}." "Java interface" "Code,Code-AccountClosure"
                            clsDormancyCheck = component "DormancyCheck" "Pass: DORMANT and inactive > konexio.dormancy.period." "Java class" "Code,Code-AccountClosure"
                            clsZeroBalanceCheck = component "ZeroBalanceCheck" "Pass: balance = 0.00. Else fix: TRANSFER_BALANCE." "Java class" "Code,Code-AccountClosure"
                            clsNoActiveLoanCheck = component "NoActiveLoanCheck" "Pass: no ACTIVE/OVERDUE loan linked. Else fix: REPAY_LOAN." "Java class" "Code,Code-AccountClosure"
                            clsClosureEligibility = component "ClosureEligibility" "List<CheckResult>; eligible() = all passed." "Java record" "Code,Code-AccountClosure"
                        }
                        group "Code: com.konexio.banking.loan" {
                            clsLoanController = component "LoanController" "offer(), accept(stepUpToken), get()." "Java class" "Code,Code-LoanDisbursement"
                            clsLoanService = component "LoanService" "offer(); @Transactional acceptAndDisburse()." "Java class" "Code,Code-LoanDisbursement"
                            clsLoanEligibility = component "LoanEligibilityPolicy" "Verified KYC, active main account, no active loan, CRB score ≥ threshold." "Java class" "Code,Code-LoanDisbursement"
                            clsLoanTerms = component "LoanTermsCalculator" "Interest, fee, total repayable, due date for KES 10,000." "Java class" "Code,Code-LoanDisbursement"
                            clsLoan = component "Loan" "loanNumber, loanAccountId, linkedAccountId, principal, terms, status." "Java class (entity)" "Code,Code-LoanDisbursement"
                            clsLoanRepo = component "LoanRepository" "existsActiveByAccountId(), save()." "Java interface" "Code,Code-AccountClosure,Code-LoanDisbursement"
                            clsCrbClient = component "CrbClient" "score(nationalIdHash) → CreditScore." "Java interface" "Code,Code-LoanDisbursement"
                        }
                        group "Code: com.konexio.banking.outbox" {
                            clsOutboxPublisher = component "OutboxPublisher" "publish(event): INSERT outbox_event in caller's transaction." "Java class" "Code,Code-TransferLedger,Code-AccountClosure,Code-LoanDisbursement"
                        }
                    }

                    # ========================== WORKER ============================
                    worker = container "Banking Worker" "Background jobs: notifications, dormancy, reconciliation, loan reminders. Reuses the ledger module." "Java 25, Spring Boot 4, Spring Batch" "Worker" {
                        group "Jobs" {
                            outboxRelay = component "Outbox Relay" "Every 2 s: claims events (SKIP LOCKED), dispatches, marks SENT." "Spring @Scheduled"
                            dormancyJob = component "Dormancy Scan Job" "Nightly: no customer activity > period → status DORMANT." "Spring Batch Job"
                            reconciliationJob = component "Reconciliation Job" "Every 5 min: resolves intents stuck in PROCESSING via M-Pesa status query." "Spring @Scheduled"
                            loanReminderJob = component "Loan Reminder Job" "Daily: due-date reminders, OVERDUE marking, CRB reporting." "Spring Batch Job"
                        }
                        group "Integration" {
                            notificationClient = component "Notification Client" "SMS + email delivery with retry and backoff." "Spring RestClient"
                        }
                    }
                }

                # ----------------------------------------------------------
                group "Data & secrets" {
                    identityDb = container "Identity Database" "Credentials, OTPs, refresh-token families, login audit." "PostgreSQL 18" "Database"
                    bankingDb = container "Core Banking Database" "Accounts, append-only ledger, intents, loans, outbox, audit. uuidv7() keys, NUMERIC(19,2)." "PostgreSQL 18" "Database"
                    vault = container "Secrets Vault" "JWT RSA keys, callback HMAC secrets, DB credentials." "HashiCorp Vault" "Vault"
                }
            }
        }

        group "Payment rails (Kenya)" {
            mpesa = softwareSystem "M-Pesa (Daraja API)" "STK Push deposits, B2C withdrawals, async result callbacks." "External"
            cardGateway = softwareSystem "Card Payment Gateway" "Visa/Mastercard debit deposits with 3-D Secure." "External"
            agentNetwork = softwareSystem "Agent Network" "Cash-in and cash-out at Konexio agents." "External"
        }

        group "Identity & credit data providers" {
            kyc = softwareSystem "KYC Provider (IPRS)" "Verifies National ID, name and DOB against the population register." "External"
            crb = softwareSystem "Credit Reference Bureau" "Credit scores in; loan performance data out." "External"
        }

        group "Messaging providers" {
            sms = softwareSystem "SMS Gateway" "OTP and transaction alert delivery." "External"
            email = softwareSystem "Email Service" "Receipts, statements, closure confirmations." "External"
        }

        # ==================================================================
        # L1/L2 RELATIONSHIPS (declared before L3 to avoid implied duplicates)
        # ==================================================================
        customer -> konexio.mobileApp "Opens account, moves money, closes accounts, borrows"
        opsStaff -> konexio.backOffice "Investigates cases; reverses failed payments"
        compliance -> konexio.backOffice "Reviews KYC, closures, audit logs"

        konexio.mobileApp -> konexio.authService "Signs up, logs in, refreshes, steps up with PIN" "HTTPS/JSON"
        konexio.mobileApp -> konexio.bankingApi "Calls banking APIs with Bearer access JWT" "HTTPS/JSON"
        konexio.backOffice -> konexio.authService "Staff login: password + TOTP MFA" "HTTPS/JSON"
        konexio.backOffice -> konexio.bankingApi "Reads accounts and audit; posts reversals" "HTTPS/JSON"

        konexio.authService -> konexio.identityDb "Stores credentials, OTPs, refresh tokens" "JDBC/TLS"
        konexio.authService -> konexio.vault "Loads RSA signing keys" "HTTPS"
        konexio.authService -> kyc "Verifies National ID at sign-up" "HTTPS/JSON"
        konexio.authService -> sms "Sends OTP codes" "HTTPS/JSON"

        konexio.bankingApi -> konexio.authService "Fetches JWKS to verify JWT signatures" "HTTPS"
        konexio.bankingApi -> konexio.bankingDb "Reads/writes accounts, ledger, intents, loans" "JDBC/TLS"
        konexio.bankingApi -> konexio.vault "Loads callback HMAC secrets" "HTTPS"
        konexio.bankingApi -> mpesa "Requests STK Push and B2C payouts" "HTTPS/JSON"
        konexio.bankingApi -> cardGateway "Creates card charges" "HTTPS/JSON"
        konexio.bankingApi -> crb "Requests credit score" "HTTPS/JSON"
        mpesa -> konexio.bankingApi "Posts payment results" "HTTPS webhook" "Async"
        cardGateway -> konexio.bankingApi "Posts charge results" "HTTPS webhook" "Async"
        agentNetwork -> konexio.bankingApi "Posts cash-in/out confirmations" "HTTPS, mTLS" "Async"

        konexio.worker -> konexio.bankingDb "Claims outbox events; updates dormancy, intents, loans" "JDBC/TLS"
        konexio.worker -> sms "Sends transaction alerts" "HTTPS/JSON"
        konexio.worker -> email "Sends receipts and closure confirmations" "HTTPS/JSON"
        konexio.worker -> mpesa "Queries status of pending payments" "HTTPS/JSON"
        konexio.worker -> crb "Reports loan performance" "HTTPS/JSON"

        # ==================================================================
        # L3 RELATIONSHIPS - AUTH SERVICE
        # ==================================================================
        konexio.mobileApp -> konexio.authService.authController "Registers, verifies OTP, logs in, refreshes" "HTTPS/JSON"
        konexio.mobileApp -> konexio.authService.stepUpController "Sends PIN + intentId before money moves" "HTTPS/JSON"
        konexio.backOffice -> konexio.authService.authController "Staff login with MFA" "HTTPS/JSON"

        konexio.authService.authSecurityConfig -> konexio.authService.pinAuthProvider "Registers in AuthenticationManager"
        konexio.authService.authSecurityConfig -> konexio.authService.loginAttemptGuard "Rate-limits /auth/** per IP and device"
        konexio.authService.authController -> konexio.authService.registrationService "Delegates sign-up steps"
        konexio.authService.authController -> konexio.authService.pinAuthProvider "Authenticates phone + PIN"
        konexio.authService.authController -> konexio.authService.tokenService "Issues, rotates, revokes tokens"
        konexio.authService.stepUpController -> konexio.authService.pinAuthProvider "Re-verifies PIN"
        konexio.authService.stepUpController -> konexio.authService.tokenService "Mints intent-bound step-up JWT"
        konexio.authService.jwksController -> konexio.authService.keyProvider "Exposes public keys"

        konexio.authService.pinAuthProvider -> konexio.authService.loginAttemptGuard "Checks lockout; records outcome"
        konexio.authService.pinAuthProvider -> konexio.authService.credentialRepo "Loads credential by phone"
        konexio.authService.pinAuthProvider -> konexio.authService.pinEncoder "Verifies PIN against Argon2id hash"
        konexio.authService.loginAttemptGuard -> konexio.authService.credentialRepo "Updates failed_attempts, locked_until"
        konexio.authService.tokenService -> konexio.authService.keyProvider "Signs with active private key (kid)"
        konexio.authService.tokenService -> konexio.authService.refreshTokenRepo "Saves hash; revokes family on reuse"
        konexio.authService.keyProvider -> konexio.vault "Loads RSA key pairs" "HTTPS"

        konexio.authService.registrationService -> konexio.authService.kycClient "Verifies identity"
        konexio.authService.registrationService -> konexio.authService.otpService "Issues and checks OTP"
        konexio.authService.registrationService -> konexio.authService.pinEncoder "Hashes new PIN"
        konexio.authService.registrationService -> konexio.authService.credentialRepo "Creates credential"
        konexio.authService.otpService -> konexio.authService.otpRepo "Stores OTP hash + expiry"
        konexio.authService.otpService -> konexio.authService.smsClient "Delivers OTP"
        konexio.authService.kycClient -> kyc "ID + name + DOB match request" "HTTPS/JSON"
        konexio.authService.smsClient -> sms "OTP message" "HTTPS/JSON"

        konexio.authService.credentialRepo -> konexio.identityDb "customer_credential, staff_credential" "JDBC/TLS"
        konexio.authService.otpRepo -> konexio.identityDb "otp_challenge" "JDBC/TLS"
        konexio.authService.refreshTokenRepo -> konexio.identityDb "refresh_token" "JDBC/TLS"

        # ==================================================================
        # L3 RELATIONSHIPS - CORE BANKING API
        # ==================================================================
        konexio.mobileApp -> konexio.bankingApi.accountController "Opens, views, closes accounts" "HTTPS/JSON"
        konexio.mobileApp -> konexio.bankingApi.paymentController "Creates and confirms payment intents" "HTTPS/JSON"
        konexio.mobileApp -> konexio.bankingApi.historyController "Lists transactions; fetches receipts" "HTTPS/JSON"
        konexio.mobileApp -> konexio.bankingApi.loanController "Gets offer; accepts loan; views loan" "HTTPS/JSON"
        konexio.backOffice -> konexio.bankingApi.accountController "Reviews accounts and closures" "HTTPS/JSON"
        konexio.backOffice -> konexio.bankingApi.historyController "Reviews transactions" "HTTPS/JSON"
        mpesa -> konexio.bankingApi.callbackController "STK Push / B2C result" "HTTPS webhook" "Async"
        cardGateway -> konexio.bankingApi.callbackController "Charge result" "HTTPS webhook" "Async"
        agentNetwork -> konexio.bankingApi.callbackController "Cash-in/out confirmation" "HTTPS, mTLS" "Async"

        konexio.bankingApi.apiSecurityConfig -> konexio.bankingApi.jwtDecoder "Validates every Bearer token"
        konexio.bankingApi.apiSecurityConfig -> konexio.bankingApi.jwtAuthConverter "Builds Authentication from claims"
        konexio.bankingApi.apiSecurityConfig -> konexio.bankingApi.ownershipAuthz "Guards /accounts/{accountId}/**"
        konexio.bankingApi.apiSecurityConfig -> konexio.bankingApi.idempotencyFilter "Adds after bearer-token filter"
        konexio.bankingApi.apiSecurityConfig -> konexio.vault "Loads callback HMAC secrets" "HTTPS"
        konexio.bankingApi.jwtDecoder -> konexio.authService.jwksController "Fetches + caches JWKS by kid" "HTTPS"
        konexio.bankingApi.ownershipAuthz -> konexio.bankingApi.accountRepo "Looks up account owner"
        konexio.bankingApi.idempotencyFilter -> konexio.bankingApi.intentRepo "Stores key; replays response"
        konexio.bankingApi.stepUpVerifier -> konexio.bankingApi.jwtDecoder "Decodes step-up JWT"
        konexio.bankingApi.stepUpVerifier -> konexio.bankingApi.intentRepo "Matches intent; burns jti"

        konexio.bankingApi.accountController -> konexio.bankingApi.accountService "Open, read, close account"
        konexio.bankingApi.accountController -> konexio.bankingApi.stepUpVerifier "Requires step-up to close"
        konexio.bankingApi.paymentController -> konexio.bankingApi.paymentService "Create and confirm intent"
        konexio.bankingApi.paymentController -> konexio.bankingApi.stepUpVerifier "Requires step-up to confirm"
        konexio.bankingApi.historyController -> konexio.bankingApi.ledgerRepo "Reads postings for history and receipts"
        konexio.bankingApi.loanController -> konexio.bankingApi.loanService "Offer, accept, read loan"
        konexio.bankingApi.loanController -> konexio.bankingApi.stepUpVerifier "Requires step-up to accept"
        konexio.bankingApi.callbackController -> konexio.bankingApi.paymentService "Completes or fails intent"

        konexio.bankingApi.accountService -> konexio.bankingApi.accountRepo "Creates, locks, closes accounts"
        konexio.bankingApi.accountService -> konexio.bankingApi.closurePolicy "Evaluates closure checks"
        konexio.bankingApi.accountService -> konexio.bankingApi.outboxPublisher "Emits AccountClosed"
        konexio.bankingApi.closurePolicy -> konexio.bankingApi.accountRepo "Reads status, balance, last activity"
        konexio.bankingApi.closurePolicy -> konexio.bankingApi.loanRepo "Checks for active loan"
        konexio.bankingApi.paymentService -> konexio.bankingApi.intentRepo "Persists intent state"
        konexio.bankingApi.paymentService -> konexio.bankingApi.ledgerService "Posts debit/credit journal"
        konexio.bankingApi.paymentService -> konexio.bankingApi.mpesaClient "Starts STK Push or B2C"
        konexio.bankingApi.paymentService -> konexio.bankingApi.cardClient "Starts card charge"
        konexio.bankingApi.paymentService -> konexio.bankingApi.outboxPublisher "Emits TransactionCompleted"
        konexio.bankingApi.loanService -> konexio.bankingApi.crbClient "Gets credit score"
        konexio.bankingApi.loanService -> konexio.bankingApi.accountService "Opens linked loan account"
        konexio.bankingApi.loanService -> konexio.bankingApi.ledgerService "Posts KES 10,000 disbursement"
        konexio.bankingApi.loanService -> konexio.bankingApi.loanRepo "Saves loan + schedule"
        konexio.bankingApi.loanService -> konexio.bankingApi.outboxPublisher "Emits LoanDisbursed"
        konexio.bankingApi.ledgerService -> konexio.bankingApi.accountRepo "Locks rows; updates balances"
        konexio.bankingApi.ledgerService -> konexio.bankingApi.ledgerRepo "Appends journal + postings"
        konexio.bankingApi.outboxPublisher -> konexio.bankingApi.outboxRepo "Inserts event"

        konexio.bankingApi.mpesaClient -> mpesa "STK Push / B2C request" "HTTPS/JSON"
        konexio.bankingApi.cardClient -> cardGateway "Charge request" "HTTPS/JSON"
        konexio.bankingApi.crbClient -> crb "Score request" "HTTPS/JSON"

        konexio.bankingApi.accountRepo -> konexio.bankingDb "account" "JDBC/TLS"
        konexio.bankingApi.intentRepo -> konexio.bankingDb "payment_intent, idempotency_key, used_step_up_jti" "JDBC/TLS"
        konexio.bankingApi.ledgerRepo -> konexio.bankingDb "journal_entry, posting" "JDBC/TLS"
        konexio.bankingApi.loanRepo -> konexio.bankingDb "loan, repayment_schedule" "JDBC/TLS"
        konexio.bankingApi.outboxRepo -> konexio.bankingDb "outbox_event" "JDBC/TLS"

        # ==================================================================
        # L3 RELATIONSHIPS - WORKER
        # ==================================================================
        konexio.worker.outboxRelay -> konexio.bankingDb "Claims PENDING events (FOR UPDATE SKIP LOCKED)" "JDBC/TLS"
        konexio.worker.outboxRelay -> konexio.worker.notificationClient "Dispatches SMS/email"
        konexio.worker.dormancyJob -> konexio.bankingDb "Sets status DORMANT" "JDBC/TLS"
        konexio.worker.reconciliationJob -> mpesa "Transaction status query" "HTTPS/JSON"
        konexio.worker.reconciliationJob -> konexio.bankingDb "Completes or fails stuck intents" "JDBC/TLS"
        konexio.worker.loanReminderJob -> konexio.bankingDb "Reads due loans; marks OVERDUE" "JDBC/TLS"
        konexio.worker.loanReminderJob -> konexio.worker.notificationClient "Sends due reminders"
        konexio.worker.loanReminderJob -> crb "Loan performance file" "HTTPS/JSON"
        konexio.worker.notificationClient -> sms "Alert SMS" "HTTPS/JSON"
        konexio.worker.notificationClient -> email "Receipt / confirmation email" "HTTPS/JSON"

        # ==================================================================
        # L4 RELATIONSHIPS - CODE: AUTH JWT
        # ==================================================================
        konexio.authService.clsAuthSecurityConfig -> konexio.authService.clsPhonePinProvider "Registers in ProviderManager"
        konexio.authService.clsAuthSecurityConfig -> konexio.authService.fwArgon2Encoder "Declares PIN encoder bean"
        konexio.authService.clsAuthSecurityConfig -> konexio.authService.fwNimbusJwtEncoder "Declares JwtEncoder bean"
        konexio.authService.clsPhonePinProvider -> konexio.authService.fwAuthenticationProvider "implements" "" "Implements"
        konexio.authService.clsPhonePinProvider -> konexio.authService.clsLoginAttemptGuard "assertNotLocked(), record*()"
        konexio.authService.clsPhonePinProvider -> konexio.authService.clsCredentialRepo "findByPhone()"
        konexio.authService.clsPhonePinProvider -> konexio.authService.fwArgon2Encoder "matches(pin, hash)"
        konexio.authService.clsPhonePinProvider -> konexio.authService.clsPhonePinToken "Reads unauthenticated; returns authenticated"
        konexio.authService.clsPhonePinToken -> konexio.authService.clsCustomerPrincipal "Holds as principal"
        konexio.authService.clsLoginAttemptGuard -> konexio.authService.clsCredentialRepo "updateFailedAttempts()"
        konexio.authService.clsJwtTokenService -> konexio.authService.clsCustomerPrincipal "Maps to sub, roles, kyc_level"
        konexio.authService.clsJwtTokenService -> konexio.authService.fwNimbusJwtEncoder "encode(JwsHeader kid, claims)"
        konexio.authService.clsJwtTokenService -> konexio.authService.clsRefreshTokenRepo "save(hash), revokeFamily()"
        konexio.authService.clsJwtTokenService -> konexio.authService.clsTokenPair "Returns"
        konexio.authService.fwNimbusJwtEncoder -> konexio.authService.clsSigningKeyProvider "Selects key via JWKSource"
        konexio.authService.clsCredentialRepo -> konexio.identityDb "customer_credential" "JDBC/TLS"
        konexio.authService.clsRefreshTokenRepo -> konexio.identityDb "refresh_token" "JDBC/TLS"

        # ==================================================================
        # L4 RELATIONSHIPS - CODE: RESOURCE SERVER
        # ==================================================================
        konexio.bankingApi.clsBankingSecurityConfig -> konexio.bankingApi.fwNimbusJwtDecoder "Builds access + step-up decoders"
        konexio.bankingApi.clsBankingSecurityConfig -> konexio.bankingApi.clsJwtConverter "Sets jwtAuthenticationConverter"
        konexio.bankingApi.clsBankingSecurityConfig -> konexio.bankingApi.clsOwnershipManager "access() on /accounts/{accountId}/**"
        konexio.bankingApi.clsBankingSecurityConfig -> konexio.bankingApi.clsIdempotencyFilter "addFilterAfter(BearerTokenAuthenticationFilter)"
        konexio.bankingApi.clsBankingSecurityConfig -> konexio.bankingApi.clsCallbackFilter "Adds to /callbacks/** chain"
        konexio.bankingApi.fwNimbusJwtDecoder -> konexio.bankingApi.clsTokenTypeValidator "Runs validator (typ)"
        konexio.bankingApi.clsJwtConverter -> konexio.bankingApi.clsKonexioPrincipal "Creates from claims"
        konexio.bankingApi.clsOwnershipManager -> konexio.bankingApi.fwAuthorizationManager "implements" "" "Implements"
        konexio.bankingApi.clsOwnershipManager -> konexio.bankingApi.clsAccountRepo "findOwnerId(accountId)"
        konexio.bankingApi.clsStepUpVerifier -> konexio.bankingApi.fwNimbusJwtDecoder "decode(stepUpToken)"

        # ==================================================================
        # L4 RELATIONSHIPS - CODE: TRANSFER & LEDGER
        # ==================================================================
        konexio.bankingApi.clsPaymentController -> konexio.bankingApi.clsStepUpVerifier "verify() before confirm"
        konexio.bankingApi.clsPaymentController -> konexio.bankingApi.clsPaymentService "createIntent(), confirm()"
        konexio.bankingApi.clsPaymentService -> konexio.bankingApi.clsPaymentIntent "Creates; drives status"
        konexio.bankingApi.clsPaymentService -> konexio.bankingApi.clsIntentRepo "save(), findByIdAndOwnerId()"
        konexio.bankingApi.clsPaymentService -> konexio.bankingApi.clsLedgerService "post(DR sender, CR recipient, DR fee)"
        konexio.bankingApi.clsPaymentService -> konexio.bankingApi.clsOutboxPublisher "TransactionCompleted (same tx)"
        konexio.bankingApi.clsPaymentIntent -> konexio.bankingApi.clsMoney "amount, fee"
        konexio.bankingApi.clsLedgerService -> konexio.bankingApi.clsAccountRepo "lockAllForUpdate(ascending ids)"
        konexio.bankingApi.clsLedgerService -> konexio.bankingApi.clsAccount "Updates ledgerBalance, lastActivityAt"
        konexio.bankingApi.clsLedgerService -> konexio.bankingApi.clsJournalEntry "Builds; assertBalanced()"
        konexio.bankingApi.clsLedgerService -> konexio.bankingApi.clsLedgerRepo "save(entry)"
        konexio.bankingApi.clsJournalEntry -> konexio.bankingApi.clsPosting "Owns 2..n postings"
        konexio.bankingApi.clsPosting -> konexio.bankingApi.clsMoney "amount, balanceAfter"
        konexio.bankingApi.clsAccountRepo -> konexio.bankingApi.clsAccount "Loads, persists"
        konexio.bankingApi.clsIntentRepo -> konexio.bankingDb "payment_intent" "JDBC/TLS"
        konexio.bankingApi.clsLedgerRepo -> konexio.bankingDb "journal_entry, posting" "JDBC/TLS"
        konexio.bankingApi.clsAccountRepo -> konexio.bankingDb "account (SELECT ... FOR UPDATE)" "JDBC/TLS"

        # ==================================================================
        # L4 RELATIONSHIPS - CODE: ACCOUNT CLOSURE
        # ==================================================================
        konexio.bankingApi.clsAccountController -> konexio.bankingApi.clsClosurePolicy "evaluate() for checklist"
        konexio.bankingApi.clsAccountController -> konexio.bankingApi.clsStepUpVerifier "verify() before close"
        konexio.bankingApi.clsAccountController -> konexio.bankingApi.clsAccountService "close(reason)"
        konexio.bankingApi.clsAccountService -> konexio.bankingApi.clsAccountRepo "Locks account row"
        konexio.bankingApi.clsAccountService -> konexio.bankingApi.clsClosurePolicy "Re-checks under lock"
        konexio.bankingApi.clsAccountService -> konexio.bankingApi.clsAccount "status CLOSED, closedAt (logical delete)"
        konexio.bankingApi.clsAccountService -> konexio.bankingApi.clsOutboxPublisher "AccountClosed (same tx)"
        konexio.bankingApi.clsClosurePolicy -> konexio.bankingApi.clsClosureCheck "Runs every check"
        konexio.bankingApi.clsClosurePolicy -> konexio.bankingApi.clsClosureEligibility "Returns"
        konexio.bankingApi.clsDormancyCheck -> konexio.bankingApi.clsClosureCheck "implements" "" "Implements"
        konexio.bankingApi.clsZeroBalanceCheck -> konexio.bankingApi.clsClosureCheck "implements" "" "Implements"
        konexio.bankingApi.clsNoActiveLoanCheck -> konexio.bankingApi.clsClosureCheck "implements" "" "Implements"
        konexio.bankingApi.clsNoActiveLoanCheck -> konexio.bankingApi.clsLoanRepo "existsActiveByAccountId()"

        # ==================================================================
        # L4 RELATIONSHIPS - CODE: LOAN DISBURSEMENT
        # ==================================================================
        konexio.bankingApi.clsLoanController -> konexio.bankingApi.clsStepUpVerifier "verify() before accept"
        konexio.bankingApi.clsLoanController -> konexio.bankingApi.clsLoanService "offer(), acceptAndDisburse()"
        konexio.bankingApi.clsLoanService -> konexio.bankingApi.clsLoanEligibility "evaluate(customer)"
        konexio.bankingApi.clsLoanEligibility -> konexio.bankingApi.clsCrbClient "score()"
        konexio.bankingApi.clsLoanEligibility -> konexio.bankingApi.clsLoanRepo "existsActiveByAccountId()"
        konexio.bankingApi.clsLoanService -> konexio.bankingApi.clsLoanTerms "terms(KES 10,000)"
        konexio.bankingApi.clsLoanService -> konexio.bankingApi.clsAccountService "openLoanAccount(linkedAccountId)"
        konexio.bankingApi.clsLoanService -> konexio.bankingApi.clsLedgerService "post(DR loan 10,000, CR main 10,000)"
        konexio.bankingApi.clsLoanService -> konexio.bankingApi.clsLoan "Creates ACTIVE loan"
        konexio.bankingApi.clsLoanService -> konexio.bankingApi.clsLoanRepo "save(loan, schedule)"
        konexio.bankingApi.clsLoanService -> konexio.bankingApi.clsOutboxPublisher "LoanDisbursed (same tx)"
        konexio.bankingApi.clsLoan -> konexio.bankingApi.clsMoney "principal, totalRepayable"
    }

    views {

        # ------------------------------ L1 ------------------------------
        systemContext konexio "SystemContext" "L1 - Konexio, its users and external dependencies." {
            include *
            autoLayout lr
        }

        # ------------------------------ L2 ------------------------------
        container konexio "Containers" "L2 - Apps, services and PostgreSQL 18 data stores." {
            include *
            autoLayout lr
        }

        # ------------------------------ L3 ------------------------------
        component konexio.authService "Components-AuthService" "L3 - Custom JWT identity provider (Spring Security 7)." {
            include *
            exclude "element.tag==Code"
            autoLayout lr
        }

        component konexio.bankingApi "Components-BankingApi" "L3 - Core Banking API." {
            include *
            exclude "element.tag==Code"
            autoLayout lr
        }

        component konexio.bankingApi "Components-BankingApi-Security" "L3 focus - JWT validation, ownership, step-up, idempotency." {
            include konexio.mobileApp konexio.authService konexio.vault
            include konexio.bankingApi.apiSecurityConfig konexio.bankingApi.jwtDecoder konexio.bankingApi.jwtAuthConverter konexio.bankingApi.ownershipAuthz konexio.bankingApi.stepUpVerifier konexio.bankingApi.idempotencyFilter
            include konexio.bankingApi.accountController konexio.bankingApi.paymentController konexio.bankingApi.loanController
            include konexio.bankingApi.accountRepo konexio.bankingApi.intentRepo
            autoLayout lr
        }

        component konexio.worker "Components-Worker" "L3 - Background jobs." {
            include *
            autoLayout lr
        }

        # ------------------------------ L4 ------------------------------
        component konexio.authService "Code-AuthJwt" "L4 - Phone + PIN authentication and JWT minting." {
            include "element.tag==Code-AuthJwt"
            include konexio.identityDb
            autoLayout lr
        }

        component konexio.bankingApi "Code-ResourceServer" "L4 - JWT validation, ownership and step-up checks." {
            include "element.tag==Code-ResourceServer"
            autoLayout lr
        }

        component konexio.bankingApi "Code-TransferLedger" "L4 - Payment intent to double-entry posting." {
            include "element.tag==Code-TransferLedger"
            include konexio.bankingDb
            autoLayout lr
        }

        component konexio.bankingApi "Code-AccountClosure" "L4 - Dormant account eligibility and logical close." {
            include "element.tag==Code-AccountClosure"
            autoLayout lr
        }

        component konexio.bankingApi "Code-LoanDisbursement" "L4 - Loan account creation and KES 10,000 disbursement." {
            include "element.tag==Code-LoanDisbursement"
            autoLayout lr
        }

        # ---------------------------- DYNAMIC ----------------------------
        dynamic konexio.authService "Dynamic-Login" "Phone + PIN login → access JWT + refresh token." {
            konexio.mobileApp -> konexio.authService.authController "POST /auth/login {phone, pin, deviceId}"
            konexio.authService.authController -> konexio.authService.pinAuthProvider "authenticate(PhonePinAuthenticationToken)"
            konexio.authService.pinAuthProvider -> konexio.authService.loginAttemptGuard "Reject if locked"
            konexio.authService.pinAuthProvider -> konexio.authService.credentialRepo "Load credential by phone"
            konexio.authService.credentialRepo -> konexio.identityDb "SELECT customer_credential"
            konexio.authService.pinAuthProvider -> konexio.authService.pinEncoder "Argon2id matches()"
            konexio.authService.authController -> konexio.authService.tokenService "Issue access (10 min) + refresh (30 d)"
            konexio.authService.tokenService -> konexio.authService.keyProvider "Sign RS256 with active kid"
            konexio.authService.tokenService -> konexio.authService.refreshTokenRepo "Save refresh hash, new family"
            konexio.authService.refreshTokenRepo -> konexio.identityDb "INSERT refresh_token"
            autoLayout lr
        }

        dynamic konexio.bankingApi "Dynamic-Deposit" "M-Pesa deposit: intent → PIN step-up → STK Push → callback → posting." {
            konexio.mobileApp -> konexio.bankingApi.paymentController "POST /payment-intents {DEPOSIT, MPESA, 5000}"
            konexio.bankingApi.paymentController -> konexio.bankingApi.paymentService "Create intent"
            konexio.bankingApi.paymentService -> konexio.bankingApi.intentRepo "Save PENDING_CONFIRMATION"
            konexio.mobileApp -> konexio.authService "POST /auth/step-up {intentId, pin}"
            konexio.mobileApp -> konexio.bankingApi.paymentController "POST /{id}/confirm + step-up JWT + Idempotency-Key"
            konexio.bankingApi.paymentController -> konexio.bankingApi.stepUpVerifier "Verify intent-bound token"
            konexio.bankingApi.paymentController -> konexio.bankingApi.paymentService "Confirm → PROCESSING"
            konexio.bankingApi.paymentService -> konexio.bankingApi.mpesaClient "Start STK Push"
            konexio.bankingApi.mpesaClient -> mpesa "STK Push to customer phone"
            mpesa -> konexio.bankingApi.callbackController "ResultCode 0 (approved)"
            konexio.bankingApi.callbackController -> konexio.bankingApi.paymentService "Complete intent"
            konexio.bankingApi.paymentService -> konexio.bankingApi.ledgerService "DR M-Pesa clearing / CR main account"
            konexio.bankingApi.paymentService -> konexio.bankingApi.outboxPublisher "DepositCompleted → SMS receipt"
            autoLayout lr
        }

        dynamic konexio.bankingApi "Dynamic-Transfer" "Internal transfer: intent → review → PIN step-up → ledger." {
            konexio.mobileApp -> konexio.bankingApi.paymentController "POST /payment-intents {TRANSFER, to, 3500} + access JWT"
            konexio.bankingApi.apiSecurityConfig -> konexio.bankingApi.jwtDecoder "Verify sig, iss, aud, exp, typ=access"
            konexio.bankingApi.paymentController -> konexio.bankingApi.paymentService "Create intent (fee, balance-after)"
            konexio.bankingApi.paymentService -> konexio.bankingApi.intentRepo "Save PENDING_CONFIRMATION"
            konexio.mobileApp -> konexio.authService "POST /auth/step-up {intentId, pin}"
            konexio.mobileApp -> konexio.bankingApi.paymentController "POST /{id}/confirm + step-up JWT + Idempotency-Key"
            konexio.bankingApi.apiSecurityConfig -> konexio.bankingApi.idempotencyFilter "Reject or replay duplicate key"
            konexio.bankingApi.paymentController -> konexio.bankingApi.stepUpVerifier "Match intent_id + amount; burn jti"
            konexio.bankingApi.paymentController -> konexio.bankingApi.paymentService "Confirm"
            konexio.bankingApi.paymentService -> konexio.bankingApi.ledgerService "DR sender / CR recipient / DR fee"
            konexio.bankingApi.ledgerService -> konexio.bankingApi.accountRepo "Lock both accounts (ascending id)"
            konexio.bankingApi.ledgerService -> konexio.bankingApi.ledgerRepo "Append journal + postings"
            konexio.bankingApi.paymentService -> konexio.bankingApi.outboxPublisher "TransferCompleted (same tx)"
            autoLayout lr
        }

        dynamic konexio.bankingApi "Dynamic-CloseAccount" "Dormant account: checklist → fix → PIN step-up → logical close." {
            konexio.mobileApp -> konexio.bankingApi.accountController "GET /accounts/{id}/closure-eligibility"
            konexio.bankingApi.accountController -> konexio.bankingApi.accountService "Evaluate eligibility"
            konexio.bankingApi.accountService -> konexio.bankingApi.closurePolicy "Run 3 checks"
            konexio.bankingApi.closurePolicy -> konexio.bankingApi.accountRepo "Dormant? Balance 0.00?"
            konexio.bankingApi.closurePolicy -> konexio.bankingApi.loanRepo "Active loan?"
            konexio.mobileApp -> konexio.authService "POST /auth/step-up {accountId intent, pin}"
            konexio.mobileApp -> konexio.bankingApi.accountController "DELETE /accounts/{id} + step-up JWT"
            konexio.bankingApi.accountController -> konexio.bankingApi.stepUpVerifier "Verify step-up"
            konexio.bankingApi.accountController -> konexio.bankingApi.accountService "Close (re-check under lock)"
            konexio.bankingApi.accountService -> konexio.bankingApi.accountRepo "status CLOSED, closed_at"
            konexio.bankingApi.accountService -> konexio.bankingApi.outboxPublisher "AccountClosed → email"
            autoLayout lr
        }

        dynamic konexio.bankingApi "Dynamic-LoanDisbursement" "Loan: offer → PIN step-up → loan account → KES 10,000 posted." {
            konexio.mobileApp -> konexio.bankingApi.loanController "GET /loan-offers"
            konexio.bankingApi.loanController -> konexio.bankingApi.loanService "Build offer"
            konexio.bankingApi.loanService -> konexio.bankingApi.crbClient "Check score"
            konexio.bankingApi.crbClient -> crb "Score request"
            konexio.mobileApp -> konexio.authService "POST /auth/step-up {offerId, pin}"
            konexio.mobileApp -> konexio.bankingApi.loanController "POST /loans + step-up JWT"
            konexio.bankingApi.loanController -> konexio.bankingApi.stepUpVerifier "Verify step-up"
            konexio.bankingApi.loanController -> konexio.bankingApi.loanService "Accept and disburse"
            konexio.bankingApi.loanService -> konexio.bankingApi.accountService "Open linked loan account"
            konexio.bankingApi.loanService -> konexio.bankingApi.ledgerService "DR loan 10,000 / CR main 10,000"
            konexio.bankingApi.loanService -> konexio.bankingApi.loanRepo "Save loan ACTIVE + schedule"
            konexio.bankingApi.loanService -> konexio.bankingApi.outboxPublisher "LoanDisbursed → SMS"
            autoLayout lr
        }

        # ----------------------------- STYLES -----------------------------
        styles {
            element "Element" {
                color #ffffff
            }
            element "Person" {
                shape Person
                background #0F5E5A
            }
            element "Software System" {
                background #16202A
            }
            element "External" {
                background #6B7580
            }
            element "Container" {
                background #1F6F6A
            }
            element "Component" {
                background #E4EFEE
                color #16202A
            }
            element "Security" {
                background #FBE9E7
                color #16202A
                stroke #B3261E
                strokeWidth 4
            }
            element "Code" {
                shape RoundedBox
                background #FFFFFF
                color #16202A
                stroke #1F6F6A
            }
            element "Framework" {
                background #F3F5F7
                border dashed
            }
            element "Database" {
                shape Cylinder
            }
            element "Mobile App" {
                shape MobileDevicePortrait
            }
            element "Web Browser" {
                shape WebBrowser
            }
            element "Worker" {
                shape Hexagon
            }
            element "Vault" {
                shape Folder
                background #3A4652
            }
            element "Group" {
                color #16202A
            }
            relationship "Relationship" {
                color #56606B
            }
            relationship "Async" {
                style dashed
            }
            relationship "Implements" {
                style dotted
                color #1F6F6A
            }
        }
    }

    configuration {
        scope softwaresystem
    }
}
