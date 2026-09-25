package com.konexio.bank.payment.domain;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.payment.RecentRecipient;
import com.konexio.bank.payment.RecipientView;
import com.konexio.bank.payment.config.PaymentProperties;
import com.konexio.bank.shared.error.RateLimitExceededException;
import com.konexio.bank.shared.util.Masks;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an account number into "is this the right person?".
 *
 * <p>The one endpoint in the system that maps an account number to a name, which
 * makes it the one worth walking through every number in the bank — so it is
 * rate limited per customer, and it answers with a masked name rather than a
 * full one. Both are in the contract for exactly this reason
 * (docs/rest-api.md §3, "rate-limited to stop account-number scanning").
 *
 * <p>The limit is per authenticated customer rather than per IP: a caller
 * scanning account numbers has to hold a token, and rationing by token is what
 * makes the scan cost them an account rather than a proxy.
 *
 * <p>In memory, so each instance counts its own. Acceptable for a coarse limit;
 * a multi-instance deployment should move it to Redis, the same note that
 * applies to the identity module's login limiter.
 */
@Service
public class RecipientLookupService {

    /**
     * Who this customer has paid, most recent first. One row per account however
     * many times it has been paid, which is what "recent people" means as opposed
     * to "recent transfers".
     */
    private static final String RECENT_SQL = """
            select destination_account_id
              from payment.payment_intent
             where customer_id = :customerId
               and intent_type = 'TRANSFER'
               and status = 'COMPLETED'
               and destination_account_id is not null
             group by destination_account_id
             order by max(completed_at) desc
             limit :limit
            """;

    private static final String SEEN_BEFORE_SQL = """
            select exists (
                select 1 from payment.payment_intent
                 where customer_id = :customerId
                   and intent_type = 'TRANSFER'
                   and destination_account_id = :accountId
                   and status = 'COMPLETED')
            """;

    private final AccountApi accountApi;
    private final CustomerApi customerApi;
    private final JdbcClient jdbcClient;
    private final PaymentProperties.RecipientLookupSettings settings;
    private final Map<UUID, Deque<Long>> lookups = new ConcurrentHashMap<>();

    RecipientLookupService(
            AccountApi accountApi,
            CustomerApi customerApi,
            JdbcClient jdbcClient,
            PaymentProperties properties) {
        this.accountApi = accountApi;
        this.customerApi = customerApi;
        this.jdbcClient = jdbcClient;
        this.settings = properties.recipientLookup();
    }

    /**
     * @throws PaymentExceptions.RecipientNotFound (404) for an unknown or closed
     *     account — the same answer either way, so the endpoint reveals only
     *     "you cannot send money here"
     * @throws RateLimitExceededException (429) once the customer has looked up
     *     too many numbers in the window
     */
    @Transactional(readOnly = true)
    public RecipientView lookup(String accountNumber, UUID customerId) {
        requireWithinRateLimit(customerId);

        AccountView account = accountApi.findByAccountNumber(accountNumber)
                .filter(candidate -> candidate.status().isOpen())
                .filter(candidate -> candidate.customerId() != null)
                .orElseThrow(PaymentExceptions.RecipientNotFound::new);

        String fullName = customerApi.find(account.customerId())
                .map(CustomerProfile::fullName)
                .orElseThrow(PaymentExceptions.RecipientNotFound::new);

        return new RecipientView(
                Masks.name(fullName),
                account.maskedNumber(),
                !hasSentBefore(customerId, account.id()));
    }

    /**
     * The customer's own recent recipients, for the list under the account-number
     * field.
     *
     * <p>Not rate limited, unlike {@link #lookup}: that limit exists to stop
     * somebody sweeping account numbers to find out who owns them, and this
     * returns only accounts this customer has already paid.
     */
    @Transactional(readOnly = true)
    public List<RecentRecipient> recent(UUID customerId, int limit) {
        return jdbcClient.sql(RECENT_SQL)
                .param("customerId", customerId)
                .param("limit", limit)
                .query(UUID.class)
                .list()
                .stream()
                .map(this::describe)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<RecentRecipient> describe(UUID accountId) {
        return accountApi.find(accountId)
                .filter(account -> account.customerId() != null)
                .map(account -> new RecentRecipient(
                        account.accountNumber(),
                        customerApi.find(account.customerId())
                                .map(profile -> Masks.name(profile.fullName()))
                                .orElse(null),
                        account.maskedNumber()));
    }

    private boolean hasSentBefore(UUID customerId, UUID destinationAccountId) {
        return Boolean.TRUE.equals(jdbcClient.sql(SEEN_BEFORE_SQL)
                .param("customerId", customerId)
                .param("accountId", destinationAccountId)
                .query(Boolean.class)
                .single());
    }

    private void requireWithinRateLimit(UUID customerId) {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - settings.window().toMillis();
        Deque<Long> timestamps = lookups.computeIfAbsent(customerId, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= settings.maxLookups()) {
                throw new RateLimitExceededException(
                        "Too many recipient checks. Try again shortly.", settings.window());
            }
            timestamps.addLast(now);
        }
    }

    /** Exposed for the rare case of a limit that needs clearing, and for tests. */
    public void resetRateLimit(UUID customerId) {
        lookups.remove(customerId);
    }

}
