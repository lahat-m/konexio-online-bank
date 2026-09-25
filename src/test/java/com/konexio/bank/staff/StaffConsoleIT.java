package com.konexio.bank.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The staff console: staff login, the read views, and the one correction the books
 * allow.
 *
 * <p>Two things are being tested at once here, and both matter. One is that a
 * member of staff can answer a customer's question — find them, see their
 * accounts, read the statement, follow a reference into the books. The other is
 * that what they cannot do is actually refused: a customer token gets nowhere
 * near these paths, a staff token gets nowhere near a customer's money, and
 * compliance can read every trail in the bank without being able to move a
 * shilling.
 */
class StaffConsoleIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID customerId;
    private String customerToken;
    private UUID mainAccountId;
    private String recipientAccountNumber;
    private String adminToken;

    @BeforeEach
    void setUp() {
        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        customerToken = String.valueOf(login(phone, PIN).get("accessToken"));
        mainAccountId = openAccountFor(customerToken, "MAIN");

        String recipientPhone = uniquePhone();
        registerCustomer(recipientPhone, uniqueNationalId(), PIN);
        String recipientToken = String.valueOf(login(recipientPhone, PIN).get("accessToken"));
        recipientAccountNumber = accountNumberOf(openAccountFor(recipientToken, "MAIN"));

        adminToken = staffToken(createStaff("ADMIN"));
    }

    // ------------------------------------------------------------------- login

    @Test
    @DisplayName("a member of staff signs in with a username and password and gets their roles back")
    void staffLoginIssuesATokenWithRoles() {
        String username = createStaff("OPS", "COMPLIANCE");

        MvcTestResult result = mvc.post().uri("/api/staff/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, PASSWORD))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        Map<String, Object> body = body(result);
        assertThat(body.get("tokenType")).isEqualTo("Bearer");
        assertThat(body.get("accessToken")).asString().isNotBlank();
        assertThat(body.get("roles")).isEqualTo(List.of("OPS", "COMPLIANCE"));
        // No refresh token: a staff session ends when the token expires.
        assertThat(body).doesNotContainKey("refreshToken");
    }

    @Test
    @DisplayName("a wrong password is a 401 that says nothing about which half was wrong")
    void refusesAWrongPassword() {
        String username = createStaff("OPS");

        MvcTestResult result = signIn(username, "not the password");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(content(result)).contains("Username or password is incorrect.");
        assertThat(countRows("""
                select count(*) from identity.security_event
                 where event_type = 'STAFF_LOGIN_FAILED' and details->>'username' = '%s'
                """.formatted(username)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown username fails exactly like a wrong password, and is still recorded")
    void refusesAnUnknownUsername() {
        MvcTestResult result = signIn("nobody.here", PASSWORD);

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(content(result)).contains("Username or password is incorrect.");
        assertThat(countRows("""
                select count(*) from identity.security_event
                 where event_type = 'STAFF_LOGIN_FAILED' and details->>'reason' = 'UNKNOWN_USERNAME'
                """))
                .isPositive();
    }

    @Test
    @DisplayName("five wrong passwords lock the account, and the right one no longer helps")
    void locksAfterTooManyWrongPasswords() {
        String username = createStaff("OPS");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(signIn(username, "wrong")).hasStatus(HttpStatus.UNAUTHORIZED);
        }
        assertThat(signIn(username, "wrong")).hasStatus(HttpStatus.LOCKED);
        assertThat(signIn(username, PASSWORD)).hasStatus(HttpStatus.LOCKED);

        assertThat(countRows("""
                select count(*) from identity.security_event
                 where event_type = 'CREDENTIAL_LOCKED' and subject_type = 'STAFF'
                   and details->>'username' = '%s'
                """.formatted(username)))
                .isEqualTo(1);
    }

    // -------------------------------------------------------------- staff reads

    @Test
    @DisplayName("customers are found by the tail of a phone number and by part of a name")
    void findsCustomers() {
        String phone = phoneOf(customerId);

        MvcTestResult byPhone = get("/api/staff/customers?q=" + phone.substring(phone.length() - 6), adminToken);
        assertThat(byPhone).hasStatus(HttpStatus.OK);
        assertThat(content(byPhone)).contains(customerId.toString());

        MvcTestResult byName = get("/api/staff/customers?q=otieno", adminToken);
        assertThat(byName).hasStatus(HttpStatus.OK);
        assertThat(body(byName).get("totalElements")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.INTEGER).isPositive();
    }

    @Test
    @DisplayName("the customer view lists every account and records who looked")
    void showsACustomerDossierAndAuditsTheLookup() {
        MvcTestResult result = get("/api/staff/customers/" + customerId, adminToken);

        assertThat(result).hasStatus(HttpStatus.OK);
        Map<String, Object> body = body(result);
        assertThat(((Map<?, ?>) body.get("customer")).get("customerId")).isEqualTo(customerId.toString());
        assertThat((List<?>) body.get("accounts")).hasSize(1);

        Map<String, Object> audit = jdbcClient
                .sql("""
                        select actor_type, actor_id, action from audit.audit_log
                         where action = 'CUSTOMER_VIEWED' and resource_id = ?
                        """)
                .param(customerId)
                .query()
                .singleRow();
        // Attributed to the person, not to the system: that is what the act claim buys.
        assertThat(audit.get("actor_type")).isEqualTo("STAFF");
        assertThat(audit.get("actor_id")).isNotNull();
    }

    @Test
    @DisplayName("an unknown customer is a 404, not an empty dossier")
    void refusesAnUnknownCustomer() {
        assertThat(get("/api/staff/customers/" + UUID.randomUUID(), adminToken))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the statement shows the customer's own lines, the same ones they see")
    void showsTheCustomerStatement() {
        completeTransfer("1500.00");

        MvcTestResult result = get(
                "/api/staff/customers/%s/statement?accountId=%s".formatted(customerId, mainAccountId),
                adminToken);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("TRANSFER");
    }

    @Test
    @DisplayName("a journal entry is found by its id and by the reference on the receipt")
    void findsAJournalEntryEitherWay() {
        UUID transferId = completeTransfer("800.00");
        UUID entryId = journalEntryOf(transferId);

        MvcTestResult byId = get("/api/staff/journal-entries/" + entryId, adminToken);
        assertThat(byId).hasStatus(HttpStatus.OK);
        String reference = string(byId, "reference");
        // Both legs of the entry, unlike the customer's statement.
        assertThat((List<?>) body(byId).get("lines")).hasSize(2);

        MvcTestResult byReference = get("/api/staff/journal-entries/" + reference, adminToken);
        assertThat(byReference).hasStatus(HttpStatus.OK);
        assertThat(string(byReference, "id")).isEqualTo(entryId.toString());
    }

    // --------------------------------------------------------------- reversals

    @Test
    @DisplayName("reversing a transfer puts the money back and leaves the original entry alone")
    void reversesATransfer() {
        UUID transferId = completeTransfer("1200.00");
        UUID entryId = journalEntryOf(transferId);
        BigDecimal balanceBefore = balanceOf(mainAccountId);

        MvcTestResult result = reverse(entryId, adminToken, "Duplicate transfer raised on ticket KNX-4821.");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/staff/journal-entries/" + string(result, "id"));
        assertThat(string(result, "entryType")).isEqualTo("REVERSAL");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo(balanceBefore.add(new BigDecimal("1200.00")));
        // The books record both movements: the original is still exactly as posted.
        assertThat(countRows("select count(*) from ledger.journal_entry where id = '%s'".formatted(entryId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same entry cannot be reversed twice — the database refuses the second one")
    void refusesASecondReversal() {
        UUID entryId = journalEntryOf(completeTransfer("300.00"));
        assertThat(reverse(entryId, adminToken, "First correction, ticket KNX-4822."))
                .hasStatus(HttpStatus.CREATED);

        assertThat(reverse(entryId, adminToken, "Second attempt at the same correction."))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a reversal is not itself reversible: moving the money again is a payment, not a correction")
    void refusesToReverseAReversal() {
        UUID entryId = journalEntryOf(completeTransfer("450.00"));
        MvcTestResult reversal = reverse(entryId, adminToken, "Wrong recipient, ticket KNX-4823.");
        assertThat(reversal).hasStatus(HttpStatus.CREATED);

        MvcTestResult second = reverse(
                UUID.fromString(string(reversal, "id")), adminToken, "Undoing the correction again.");

        assertThat(second).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(second)).contains("itself a reversal");
    }

    @Test
    @DisplayName("a reason is required, and long enough to mean something on a statement")
    void refusesAReversalWithoutAReason() {
        UUID entryId = journalEntryOf(completeTransfer("200.00"));

        assertThat(reverse(entryId, adminToken, "oops")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("reversing something that was never posted is a 404")
    void refusesToReverseAnUnknownEntry() {
        assertThat(reverse(UUID.randomUUID(), adminToken, "Correcting an entry that does not exist."))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------- compliance

    @Test
    @DisplayName("compliance reads the audit log, filtered to one resource")
    void readsTheAuditLog() {
        String complianceToken = staffToken(createStaff("COMPLIANCE"));
        completeTransfer("640.00");

        MvcTestResult result = get(
                "/api/staff/events?resourceType=ACCOUNT&resourceId=" + mainAccountId, complianceToken);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("ACCOUNT_OPENED");
    }

    @Test
    @DisplayName("compliance reads the security trail, filtered to one subject")
    void readsTheSecurityTrail() {
        String complianceToken = staffToken(createStaff("COMPLIANCE"));

        MvcTestResult result = get(
                "/api/staff/security-events?subjectId=%s&eventType=LOGIN_SUCCEEDED".formatted(customerId),
                complianceToken);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(body(result).get("totalElements")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.INTEGER).isPositive();
        // The trail never held a full phone number, so neither does this.
        assertThat(content(result)).doesNotContain(phoneOf(customerId));
    }

    @Test
    @DisplayName("a filter naming something that does not exist is a 400, not an empty page")
    void refusesAnUnknownFilterValue() {
        String complianceToken = staffToken(createStaff("COMPLIANCE"));

        assertThat(get("/api/staff/events?resourceType=ACOUNT", complianceToken))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------- roles

    @Test
    @DisplayName("operations cannot read the trails, and compliance cannot touch the books")
    void keepsTheTwoRolesApart() {
        String opsToken = staffToken(createStaff("OPS"));
        String complianceToken = staffToken(createStaff("COMPLIANCE"));
        UUID entryId = journalEntryOf(completeTransfer("150.00"));

        assertThat(get("/api/staff/events", opsToken)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(reverse(entryId, complianceToken, "Compliance should not be able to do this."))
                .hasStatus(HttpStatus.FORBIDDEN);

        // Each can still do its own job.
        assertThat(get("/api/staff/customers/" + customerId, opsToken)).hasStatus(HttpStatus.OK);
        assertThat(get("/api/staff/events", complianceToken)).hasStatus(HttpStatus.OK);
    }

    @Test
    @DisplayName("a customer's token gets nowhere near the staff console")
    void refusesACustomerToken() {
        assertThat(get("/api/staff/customers/" + customerId, customerToken))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("/api/staff/events", customerToken)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a staff token gets nowhere near a customer's money")
    void refusesAStaffTokenOnTheCustomerApi() {
        assertThat(get("/api/accounts", adminToken)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("/api/transactions", adminToken)).hasStatus(HttpStatus.FORBIDDEN);

        MvcTestResult transfer = mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"toAccountNumber":"%s","amount":"10.00"}
                        """.formatted(recipientAccountNumber))
                .exchange();
        assertThat(transfer).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the staff console needs a token like everything else")
    void refusesAnAnonymousCaller() {
        assertThat(mvc.get().uri("/api/staff/customers").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ----------------------------------------------------------------- helpers

    /** A staff account with a unique username, since staff rows outlive a test. */
    private String createStaff(String... roles) {
        String username = "staff." + UUID.randomUUID().toString().substring(0, 8);
        jdbcClient.sql("""
                insert into identity.staff_credential (username, full_name, email, password_hash, roles, status)
                values (:username, 'Wanjiru Kamau', :email, :hash, cast(:roles as text[]), 'ACTIVE')
                """)
                .param("username", username)
                .param("email", username + "@konexio.example")
                .param("hash", passwordEncoder.encode(PASSWORD))
                .param("roles", "{" + String.join(",", roles) + "}")
                .update();
        return username;
    }

    private String staffToken(String username) {
        MvcTestResult result = signIn(username, PASSWORD);
        assertThat(result).hasStatus(HttpStatus.OK);
        return string(result, "accessToken");
    }

    private MvcTestResult signIn(String username, String password) {
        return mvc.post().uri("/api/staff/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password))
                .exchange();
    }

    private MvcTestResult get(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult reverse(UUID journalEntryId, String token, String reason) {
        return mvc.post().uri("/api/staff/journal-entries/{id}/reversals", journalEntryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"%s\"}".formatted(reason))
                .exchange();
    }

    private UUID completeTransfer(String amount) {
        fund(mainAccountId, "10000.00");
        MvcTestResult created = mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"toAccountNumber":"%s","amount":"%s"}
                        """.formatted(recipientAccountNumber, amount))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID transferId = UUID.fromString(string(created, "id"));

        assertThat(mvc.post().uri("/api/transfers/{id}/confirmation", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                .header("Step-Up-Token", stepUpToken(customerToken, "TRANSFER", transferId, PIN))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange())
                .hasStatus(HttpStatus.OK);
        return transferId;
    }

    private UUID journalEntryOf(UUID transferId) {
        return jdbcClient
                .sql("select id from ledger.journal_entry where source_type = 'PAYMENT' and source_id = ?")
                .param(transferId)
                .query(UUID.class)
                .single();
    }

    private String phoneOf(UUID customer) {
        return jdbcClient.sql("select phone from customer.customer where id = ?")
                .param(customer)
                .query(String.class)
                .single();
    }

    private String accountNumberOf(UUID accountId) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(accountId)
                .query(String.class)
                .single();
    }

    /** Puts money in an account the way a completed deposit would, without one. */
    private void fund(UUID accountId, String amount) {
        UUID entryId = jdbcClient
                .sql("""
                        insert into ledger.journal_entry (entry_type, source_type, source_id, description)
                        values ('DEPOSIT', 'ADJUSTMENT', ?, 'Test funding')
                        returning id
                        """)
                .param(UUID.randomUUID())
                .query(UUID.class)
                .single();
        jdbcClient.sql("""
                insert into ledger.posting (journal_entry_id, account_id, direction, amount, currency)
                values (?, ?, 'DEBIT', ?::numeric, 'KES'), (?, ?, 'CREDIT', ?::numeric, 'KES')
                """)
                .params(List.of(
                        entryId, internalAccountId("MPESA_CLEARING"), amount,
                        entryId, accountId, amount))
                .update();
    }
}
