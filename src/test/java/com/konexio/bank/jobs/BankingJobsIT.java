package com.konexio.bank.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import com.konexio.bank.jobs.domain.BankingJobs;
import com.konexio.bank.jobs.domain.JobRunner;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The six scheduled jobs.
 *
 * <p>Driven directly rather than by waiting for a timer — the timers are off in
 * tests for exactly that reason. What is worth testing is that each job finds
 * the right rows, changes them, and leaves a history entry saying it did.
 */
class BankingJobsIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Autowired
    private BankingJobs jobs;

    @Autowired
    private JobRunner jobRunner;

    private UUID customerId;
    private String accessToken;
    private UUID mainAccountId;

    @BeforeEach
    void setUp() {
        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        mainAccountId = openAccountFor(accessToken, "MAIN");
        releaseAllLocks();
    }

    @Test
    @DisplayName("the dormancy scan marks accounts nobody has touched, and records the run")
    void runsTheDormancyScan() {
        age(mainAccountId);

        JobRunner.JobOutcome outcome = jobs.dormancyScan();

        assertThat(outcome.ran()).isTrue();
        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.items()).isPositive();
        assertThat(statusOfAccount(mainAccountId)).isEqualTo("DORMANT");

        Map<String, Object> run = lastRun("DORMANCY_SCAN");
        assertThat(run.get("status")).isEqualTo("SUCCEEDED");
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(((Number) run.get("items_written")).intValue()).isPositive();
        assertThat(run.get("worker_instance")).isNotNull();
    }

    @Test
    @DisplayName("the expiry job retires quotes nobody confirmed, which a read would never reach")
    void expiresAbandonedQuotes() {
        UUID transferId = createTransfer();
        expire(transferId);

        JobRunner.JobOutcome outcome = jobs.intentExpiry();

        assertThat(outcome.items()).isPositive();
        assertThat(statusOfIntent(transferId)).isEqualTo("EXPIRED");
        assertThat(lastRun("INTENT_EXPIRY").get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("reconciliation applies a callback that could not be applied when it arrived")
    void reappliesAStoredCallback() {
        UUID depositId = confirmedDeposit("4000.00");
        String reference = externalReferenceOf(depositId);

        // A callback that arrived and was stored, but never processed — which is
        // what the endpoint leaves behind when applying it throws.
        storeUnprocessedCallback(reference);

        JobRunner.JobOutcome outcome = jobs.paymentReconciliation();

        assertThat(outcome.items()).isPositive();
        assertThat(statusOfIntent(depositId)).isEqualTo("COMPLETED");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("4000.00");
        assertThat(countRows("""
                select count(*) from payment.provider_callback
                 where external_reference = '%s' and processed_at is not null
                """.formatted(reference)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("reconciliation reports payments the provider never answered for, without guessing")
    void reportsStuckPayments() {
        UUID depositId = confirmedDeposit("1000.00");
        jdbcClient.sql("update payment.payment_intent set updated_at = now() - interval '2 hours' where id = ?")
                .param(depositId)
                .update();

        jobs.paymentReconciliation();

        // Found and logged, but deliberately not settled either way: only the
        // provider knows whether the money moved.
        assertThat(statusOfIntent(depositId)).isEqualTo("PROCESSING");
        assertThat(lastRun("PAYMENT_RECONCILIATION").get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("the reminder job warns about loans coming due and marks the ones past it")
    void remindsAndMarksOverdueLoans() {
        UUID loanId = disburseLoan();

        // Due tomorrow: within the three-day reminder window.
        setDueDate(loanId, 1);
        JobRunner.JobOutcome reminded = jobs.loanReminder();
        assertThat(reminded.items()).isPositive();
        assertThat(outboxEventTypes(loanId)).contains("LoanDueSoon");
        assertThat(statusOfLoan(loanId)).isEqualTo("ACTIVE");

        // Due yesterday: overdue.
        setDueDate(loanId, -1);
        jobs.loanReminder();
        assertThat(statusOfLoan(loanId)).isEqualTo("OVERDUE");
        assertThat(outboxEventTypes(loanId)).contains("LoanOverdue");
        assertThat(jdbcClient.sql("select overdue_since from loan.loan where id = ?")
                .param(loanId)
                .query(java.time.LocalDate.class)
                .single())
                .isNotNull();
    }

    @Test
    @DisplayName("a loan already overdue is not marked, or announced, twice")
    void doesNotRepeatTheOverdueMark() {
        UUID loanId = disburseLoan();
        setDueDate(loanId, -1);

        jobs.loanReminder();
        jobs.loanReminder();

        assertThat(countRows("""
                select count(*) from outbox.outbox_event
                 where aggregate_id = '%s' and event_type = 'LoanOverdue'
                """.formatted(loanId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CRB reporting records one submission per loan per day, however often it runs")
    void reportsEachLoanOncePerDay() {
        UUID loanId = disburseLoan();

        JobRunner.JobOutcome first = jobs.crbReporting();
        assertThat(first.items()).isPositive();

        releaseAllLocks();
        jobs.crbReporting();

        assertThat(countRows("select count(*) from loan.crb_submission where loan_id = '%s'".formatted(loanId)))
                .isEqualTo(1);
        Map<String, Object> submission = jdbcClient
                .sql("select reported_status, outstanding_amount, crb_reference from loan.crb_submission"
                        + " where loan_id = ?")
                .param(loanId)
                .query()
                .singleRow();
        assertThat(submission.get("reported_status")).isEqualTo("ACTIVE");
        assertThat((java.math.BigDecimal) submission.get("outstanding_amount")).isPositive();
        // Nothing was actually submitted: no bureau is connected.
        assertThat(submission.get("crb_reference")).isNull();
    }

    @Test
    @DisplayName("housekeeping drops spent idempotency keys")
    void purgesExpiredIdempotencyKeys() {
        openAccountFor(accessToken, "SAVINGS");
        jdbcClient.sql("update api_security.idempotency_key set expires_at = now() - interval '1 day'").update();

        JobRunner.JobOutcome outcome = jobs.housekeeping();

        assertThat(outcome.items()).isPositive();
        assertThat(countRows("select count(*) from api_security.idempotency_key")).isZero();
    }

    @Test
    @DisplayName("only one instance runs a job: the second finds the lock taken")
    void locksOutASecondRunner() {
        age(mainAccountId);
        holdLock("DORMANCY_SCAN");

        long runsBefore = countRows("select count(*) from jobs.job_run where job_name = 'DORMANCY_SCAN'");

        JobRunner.JobOutcome outcome = jobs.dormancyScan();

        assertThat(outcome.ran()).isFalse();
        assertThat(statusOfAccount(mainAccountId)).isEqualTo("ACTIVE");
        // Skipped means skipped: no history row for a run that never happened.
        assertThat(countRows("select count(*) from jobs.job_run where job_name = 'DORMANCY_SCAN'"))
                .isEqualTo(runsBefore);
    }

    @Test
    @DisplayName("the lock is released afterwards, so the next run is not blocked by the last one")
    void releasesTheLock() {
        assertThat(jobs.housekeeping().ran()).isTrue();
        assertThat(jobs.housekeeping().ran()).isTrue();

        Map<String, Object> lock = jdbcClient
                .sql("select locked_by, lock_until from jobs.shedlock where name = 'HOUSEKEEPING'")
                .query()
                .singleRow();
        assertThat(lock.get("locked_by")).isNotNull();
        assertThat(lock.get("lock_until")).isNotNull();
    }

    @Test
    @DisplayName("a job that throws is recorded as FAILED with a reason, not silently lost")
    void recordsAFailedRun() {
        JobRunner runner = jobRunner;

        JobRunner.JobOutcome outcome = runner.run(JobName.HOUSEKEEPING, () -> {
            throw new IllegalStateException("the database fell over");
        });

        assertThat(outcome.ran()).isTrue();
        assertThat(outcome.succeeded()).isFalse();
        Map<String, Object> run = lastRun("HOUSEKEEPING");
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("error_message")).isEqualTo("the database fell over");
        assertThat(run.get("finished_at")).isNotNull();
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Locks survive between tests in a shared context. Deleting the rows rather
     * than expiring them leaves no doubt about whether a lock is free.
     */
    private void releaseAllLocks() {
        jdbcClient.sql("delete from jobs.shedlock").update();
    }

    private void holdLock(String name) {
        jdbcClient.sql("""
                insert into jobs.shedlock (name, lock_until, locked_at, locked_by)
                values (?, (now() at time zone 'UTC') + interval '10 minutes',
                        now() at time zone 'UTC', 'another-instance')
                on conflict (name) do update
                   set lock_until = (now() at time zone 'UTC') + interval '10 minutes',
                       locked_by = 'another-instance'
                """)
                .param(name)
                .update();
    }

    private void age(UUID accountId) {
        jdbcClient.sql("update account.account set last_customer_activity_at = now() - interval '18 months'"
                        + " where id = ?")
                .param(accountId)
                .update();
    }

    private UUID createTransfer() {
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));
        String toAccountNumber = jdbcClient.sql("select account_number from account.account where id = ?")
                .param(openAccountFor(otherToken, "MAIN"))
                .query(String.class)
                .single();

        fund(mainAccountId, "100.00");
        MvcTestResult created = mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toAccountNumber\":\"%s\",\"amount\":\"10.00\"}".formatted(toAccountNumber))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(string(created, "id"));
    }

    private UUID confirmedDeposit(String amount) {
        MvcTestResult created = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"channel":"MPESA","amount":"%s","msisdn":"+254712345678"}
                        """.formatted(amount))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID depositId = UUID.fromString(string(created, "id"));

        assertThat(mvc.post().uri("/api/deposits/{id}/confirmation", depositId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, "DEPOSIT", depositId, PIN))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange())
                .hasStatus(HttpStatus.ACCEPTED);
        return depositId;
    }

    private void storeUnprocessedCallback(String externalReference) {
        jdbcClient.sql("""
                insert into payment.provider_callback
                    (provider, callback_type, external_reference, source_ip, signature_valid, payload)
                values ('MPESA', 'STK_RESULT', ?, '127.0.0.1'::inet, true, cast(? as jsonb))
                """)
                .params(List.of(
                        externalReference,
                        """
                        {"externalReference":"%s","successful":true,"resultCode":"0","resultDescription":"Ok"}
                        """.formatted(externalReference)))
                .update();
    }

    private UUID disburseLoan() {
        jdbcClient.sql("""
                insert into loan.loan_product_pricing
                    (product_id, valid_during, interest_rate, processing_fee, approved_by)
                select id, daterange(current_date - 1, null), 0.0500, 250.00, 'integration-test'
                  from loan.loan_product where code = 'INSTANT_10K'
                """).update();

        MvcTestResult offers = mvc.get().uri("/api/loan-offers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
        assertThat(offers).hasStatus(HttpStatus.OK);
        UUID offerId = UUID.fromString(String.valueOf(firstOffer(offers).get("id")));

        MvcTestResult accepted = mvc.post().uri("/api/loans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Step-Up-Token", stepUpToken(accessToken, "LOAN_ACCEPTANCE", offerId, PIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":\"%s\",\"termsAccepted\":true}".formatted(offerId))
                .exchange();
        assertThat(accepted).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(string(accepted, "id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstOffer(MvcTestResult result) {
        return ((List<Map<String, Object>>) objectMapper.readValue(content(result), List.class)).getFirst();
    }

    private void setDueDate(UUID loanId, int daysFromToday) {
        jdbcClient.sql("update loan.loan set due_date = current_date + ? where id = ?")
                .params(List.of(daysFromToday, loanId))
                .update();
    }

    private void expire(UUID intentId) {
        jdbcClient.sql("update payment.payment_intent set expires_at = now() - interval '1 minute' where id = ?")
                .param(intentId)
                .update();
    }

    private String externalReferenceOf(UUID intentId) {
        return jdbcClient.sql("select external_reference from payment.payment_intent where id = ?")
                .param(intentId)
                .query(String.class)
                .single();
    }

    private String statusOfAccount(UUID accountId) {
        return jdbcClient.sql("select status from account.account where id = ?")
                .param(accountId)
                .query(String.class)
                .single();
    }

    private String statusOfIntent(UUID intentId) {
        return jdbcClient.sql("select status from payment.payment_intent where id = ?")
                .param(intentId)
                .query(String.class)
                .single();
    }

    private String statusOfLoan(UUID loanId) {
        return jdbcClient.sql("select status from loan.loan where id = ?")
                .param(loanId)
                .query(String.class)
                .single();
    }

    private List<String> outboxEventTypes(UUID aggregateId) {
        return jdbcClient.sql("select event_type from outbox.outbox_event where aggregate_id = ?")
                .param(aggregateId)
                .query(String.class)
                .list();
    }

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

    private Map<String, Object> lastRun(String jobName) {
        return jdbcClient
                .sql("""
                        select status, finished_at, items_written, worker_instance, error_message
                          from jobs.job_run
                         where job_name = ?
                         order by started_at desc
                         limit 1
                        """)
                .param(jobName)
                .query()
                .singleRow();
    }
}
