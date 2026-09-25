package com.konexio.bank.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import com.konexio.bank.notification.domain.OutboxRelayRunner;
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
 * The transactional outbox and the relay that drains it.
 *
 * <p>Every event here is produced by driving a real flow, because the property
 * worth testing is the one that only holds when the row and the change share a
 * transaction: a transfer that committed has a message queued, and one that was
 * refused has nothing.
 */
class NotificationRelayIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Autowired
    private OutboxRelayRunner relay;

    @Autowired
    private NotificationApi notificationApi;

    private UUID customerId;
    private String accessToken;
    private UUID mainAccountId;
    private String recipientAccountNumber;

    @BeforeEach
    void setUp() {
        // Half of these assertions are about the queue as a whole — what a drain
        // found, and what it left behind — so they only mean anything from an
        // empty outbox. Every other suite banks events here as it runs, and this
        // class's own backoff test parks a FAILED row for the next one to trip
        // over. Deliveries reference their event, so they go first.
        jdbcClient.sql("delete from outbox.notification_delivery").update();
        jdbcClient.sql("delete from outbox.outbox_event").update();

        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        mainAccountId = openAccountFor(accessToken, "MAIN");

        String recipientPhone = uniquePhone();
        registerCustomer(recipientPhone, uniqueNationalId(), PIN);
        String recipientToken = String.valueOf(login(recipientPhone, PIN).get("accessToken"));
        recipientAccountNumber = accountNumberOf(openAccountFor(recipientToken, "MAIN"));
    }

    @Test
    @DisplayName("signing up and opening an account queue events in the same transaction")
    void queuesEventsFromRealFlows() {
        // Both already happened in setUp.
        assertThat(eventTypesFor(customerId)).contains("CustomerRegistered");
        assertThat(eventTypesFor(mainAccountId)).contains("AccountOpened");
        assertThat(statusOf(mainAccountId, "AccountOpened")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a refused payment leaves no message behind promising money that never moved")
    void queuesNothingForARolledBackChange() {
        long before = countRows("select count(*) from outbox.outbox_event where aggregate_type = 'PAYMENT'");

        MvcTestResult refused = mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"toAccountNumber":"%s","amount":"9999.00"}
                        """.formatted(recipientAccountNumber))
                .exchange();

        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(countRows("select count(*) from outbox.outbox_event where aggregate_type = 'PAYMENT'"))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the relay sends what is queued, records a delivery, and marks the event SENT")
    void drainsTheOutbox() {
        UUID transferId = completeTransfer("1500.00");

        int handled = relay.drain();

        assertThat(handled).isPositive();
        assertThat(statusOf(transferId, "PaymentCompleted")).isEqualTo("SENT");

        Map<String, Object> delivery = jdbcClient
                .sql("""
                        select d.channel, d.template_code, d.status, d.recipient_masked,
                               d.provider_message_id, d.recipient_hash
                          from outbox.notification_delivery d
                          join outbox.outbox_event e on e.id = d.outbox_event_id
                         where e.aggregate_id = ? and e.event_type = 'PaymentCompleted'
                        """)
                .param(transferId)
                .query()
                .singleRow();

        assertThat(delivery.get("channel")).isEqualTo("SMS");
        assertThat(delivery.get("template_code")).isEqualTo("TRANSFER_SENT_SMS");
        assertThat(delivery.get("status")).isEqualTo("SENT");
        assertThat(delivery.get("provider_message_id")).isNotNull();
        // The recipient is kept hashed and masked, never in full.
        assertThat(String.valueOf(delivery.get("recipient_masked"))).contains("•");
        assertThat(delivery.get("recipient_hash")).isNotNull();
    }

    @Test
    @DisplayName("a second pass finds nothing: a sent event is not sent again")
    void doesNotResend() {
        completeTransfer("500.00");
        assertThat(relay.drain()).isPositive();

        assertThat(relay.drain()).isZero();
        assertThat(countRows("select count(*) from outbox.outbox_event where status in ('PENDING','FAILED')"))
                .isZero();
    }

    @Test
    @DisplayName("each channel gets one delivery row per event, however often the relay runs")
    void recordsOneDeliveryPerChannel() {
        UUID transferId = completeTransfer("700.00");
        relay.drain();
        relay.drain();

        assertThat(countRows("""
                select count(*) from outbox.notification_delivery d
                  join outbox.outbox_event e on e.id = d.outbox_event_id
                 where e.aggregate_id = '%s'
                """.formatted(transferId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a closed account sends the customer their closure reference by email")
    void emailsAClosureReference() {
        UUID savingsAccountId = openAccountFor(accessToken, "SAVINGS");
        makeDormant(savingsAccountId);

        MvcTestResult closed = mvc.delete().uri("/api/accounts/{id}?reason=NOT_USED", savingsAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, "ACCOUNT_CLOSURE", savingsAccountId, PIN))
                .exchange();
        assertThat(closed).hasStatus(HttpStatus.OK);

        relay.drain();

        Map<String, Object> delivery = jdbcClient
                .sql("""
                        select d.channel, d.template_code, d.status
                          from outbox.notification_delivery d
                          join outbox.outbox_event e on e.id = d.outbox_event_id
                         where e.aggregate_id = ? and e.event_type = 'AccountClosed'
                        """)
                .param(savingsAccountId)
                .query()
                .singleRow();

        assertThat(delivery.get("channel")).isEqualTo("EMAIL");
        assertThat(delivery.get("template_code")).isEqualTo("ACCOUNT_CLOSED_EMAIL");
        assertThat(delivery.get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("an event nobody has a template for is settled rather than retried forever")
    void settlesEventsWithNoTemplate() {
        UUID eventId = queueRaw("AccountReviewed", Map.of("customerId", customerId.toString()));

        relay.drain();

        assertThat(statusById(eventId)).isEqualTo("SENT");
        assertThat(countRows(
                "select count(*) from outbox.notification_delivery where outbox_event_id = '%s'".formatted(eventId)))
                .isZero();
    }

    @Test
    @DisplayName("an unreadable payload is retried with a backoff, then given up on")
    void backsOffThenGivesUp() {
        UUID eventId = queueBrokenPayload();

        // maxAttempts is 8; each pass has to be made due again, since a failure
        // pushes next_attempt_at forward on purpose.
        for (int attempt = 1; attempt <= 8; attempt++) {
            makeDue(eventId);
            relay.drain();
        }

        assertThat(statusById(eventId)).isEqualTo("DEAD");
        assertThat(jdbcClient.sql("select attempts from outbox.outbox_event where id = ?")
                .param(eventId)
                .query(Integer.class)
                .single())
                .isEqualTo(8);
    }

    @Test
    @DisplayName("a failure is not retried immediately: the backoff is real")
    void honoursTheBackoff() {
        UUID eventId = queueBrokenPayload();

        relay.drain();
        assertThat(statusById(eventId)).isEqualTo("FAILED");

        // Still failed, but not picked up again — its next attempt is in the future.
        assertThat(relay.drain()).isZero();
    }

    @Test
    @DisplayName("housekeeping removes delivered events and leaves the ones still owed a retry")
    void purgesOnlySentEvents() {
        completeTransfer("100.00");
        relay.drain();
        UUID failed = queueBrokenPayload();
        relay.drain();

        jdbcClient.sql("update outbox.outbox_event set sent_at = now() - interval '60 days' where status = 'SENT'")
                .update();
        int purged = notificationApi.purgeSentOlderThan(java.time.Duration.ofDays(30));

        assertThat(purged).isPositive();
        assertThat(countRows("select count(*) from outbox.outbox_event where status = 'SENT'")).isZero();
        assertThat(statusById(failed)).isEqualTo("FAILED");
    }

    // ----------------------------------------------------------------- helpers

    private UUID completeTransfer(String amount) {
        fund(mainAccountId, "10000.00");
        MvcTestResult created = mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"toAccountNumber":"%s","amount":"%s"}
                        """.formatted(recipientAccountNumber, amount))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID transferId = UUID.fromString(string(created, "id"));

        assertThat(mvc.post().uri("/api/transfers/{id}/confirmation", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, "TRANSFER", transferId, PIN))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange())
                .hasStatus(HttpStatus.OK);
        return transferId;
    }

    /** Queues an event directly, for the cases a real flow cannot produce. */
    private UUID queueRaw(String eventType, Map<String, Object> payload) {
        return jdbcClient
                .sql("""
                        insert into outbox.outbox_event (aggregate_type, aggregate_id, event_type, payload)
                        values ('CUSTOMER', ?, ?, cast(? as jsonb))
                        returning id
                        """)
                .params(List.of(customerId, eventType, objectMapper.writeValueAsString(payload)))
                .query(UUID.class)
                .single();
    }

    /** A payload that is valid jsonb and not a JSON object, so reading it into a map throws. */
    private UUID queueBrokenPayload() {
        return jdbcClient
                .sql("""
                        insert into outbox.outbox_event (aggregate_type, aggregate_id, event_type, payload)
                        values ('CUSTOMER', ?, 'CustomerRegistered', '"not an object"'::jsonb)
                        returning id
                        """)
                .param(customerId)
                .query(UUID.class)
                .single();
    }

    private void makeDue(UUID eventId) {
        jdbcClient.sql("update outbox.outbox_event set next_attempt_at = now() - interval '1 second' where id = ?")
                .param(eventId)
                .update();
    }

    private String statusById(UUID eventId) {
        return jdbcClient.sql("select status from outbox.outbox_event where id = ?")
                .param(eventId)
                .query(String.class)
                .single();
    }

    private String statusOf(UUID aggregateId, String eventType) {
        return jdbcClient
                .sql("select status from outbox.outbox_event where aggregate_id = ? and event_type = ?")
                .params(List.of(aggregateId, eventType))
                .query(String.class)
                .single();
    }

    private List<String> eventTypesFor(UUID aggregateId) {
        return jdbcClient.sql("select event_type from outbox.outbox_event where aggregate_id = ?")
                .param(aggregateId)
                .query(String.class)
                .list();
    }

    private String accountNumberOf(UUID accountId) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(accountId)
                .query(String.class)
                .single();
    }

    private void makeDormant(UUID accountId) {
        jdbcClient.sql("update account.account set last_customer_activity_at = now() - interval '18 months'"
                        + " where id = ?")
                .param(accountId)
                .update();
        jdbcClient.sql("select account.mark_dormant_accounts(interval '12 months')")
                .query(Integer.class)
                .single();
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
}
