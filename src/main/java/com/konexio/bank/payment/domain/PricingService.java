package com.konexio.bank.payment.domain;

import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.PaymentChannel;
import com.konexio.bank.payment.TransactionLimits;
import com.konexio.bank.shared.money.Money;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a payment costs, and whether it is allowed to be that big.
 *
 * <p>Both answers come from tables that are deliberately empty in a fresh
 * database: {@code fee_rule} and {@code transaction_limit} are policy, inserted
 * with an approver's name in their own migration, never seeded
 * (V17, "Not seeded on purpose"). So this has to behave sensibly with
 * nothing configured, and it does the only two things that are defensible:
 *
 * <ul>
 *   <li>no fee rule matches → the payment is free. Charging a customer a number
 *       nobody approved would be worse than charging nothing.
 *   <li>no limit row matches → the payment is allowed, and a warning is logged
 *       once per combination. The alternative, refusing everything until
 *       somebody inserts a row, makes a fresh environment look broken rather
 *       than unconfigured.
 * </ul>
 *
 * <p>That second one is a real gap in an environment with customers in it, and
 * it is loud rather than silent for exactly that reason.
 */
@Service
class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    /**
     * {@code ex_fee_rule_no_overlap} guarantees at most one row matches, so this
     * needs no tie-break and no ordering — if two ever matched, the database
     * would have refused the second one at insert.
     */
    private static final String FEE_SQL = """
            select fee from payment.fee_rule
             where intent_type = :intentType
               and channel     = :channel
               and currency    = :currency
               and :amount between min_amount and max_amount
               and valid_from <= current_date
               and (valid_to is null or valid_to > current_date)
            """;

    /** The temporal primary key guarantees at most one row per day. */
    private static final String LIMIT_SQL = """
            select min_amount, max_amount, daily_max_amount
              from payment.transaction_limit
             where intent_type = :intentType
               and channel     = :channel
               and kyc_level   = :kycLevel
               and currency    = :currency
               and valid_during @> current_date
            """;

    /**
     * What the customer has already put through this type of payment today.
     * Counts {@code PROCESSING} as spent: a payout that is with the provider has
     * left, whatever the callback eventually says.
     */
    private static final String SPENT_TODAY_SQL = """
            select coalesce(sum(amount + fee), 0)
              from payment.payment_intent
             where customer_id = :customerId
               and intent_type = :intentType
               and status in ('PROCESSING', 'COMPLETED')
               and created_at >= date_trunc('day', now())
               and (:excludeIntentId::uuid is null or id <> :excludeIntentId::uuid)
            """;

    private final JdbcClient jdbcClient;

    /** One warning per unconfigured combination, not one per request. */
    private final Set<String> warnedAbout = ConcurrentHashMap.newKeySet();

    PricingService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    Money feeFor(IntentType intentType, PaymentChannel channel, Money amount) {
        return jdbcClient.sql(FEE_SQL)
                .param("intentType", intentType.name())
                .param("channel", channel.name())
                .param("currency", amount.currency())
                .param("amount", amount.amount())
                .query(BigDecimal.class)
                .optional()
                .map(fee -> Money.of(fee, amount.currency()))
                .orElseGet(() -> Money.zero(amount.currency()));
    }

    /**
     * Refuses a payment that is outside the approved band, or that would take the
     * customer past today's total.
     *
     * @param excludeIntentId the intent being re-quoted, so editing an amount
     *     does not count the old one against the new one
     */
    @Transactional(readOnly = true)
    void checkLimits(
            UUID customerId,
            String kycLevel,
            IntentType intentType,
            PaymentChannel channel,
            Money amount,
            Money fee,
            UUID excludeIntentId) {
        Optional<Limit> configured = findLimit(intentType, channel, kycLevel, amount.currency());
        if (configured.isEmpty()) {
            warnOnce(intentType, channel, kycLevel);
            return;
        }
        Limit limit = configured.get();
        if (amount.amount().compareTo(limit.minAmount()) < 0) {
            throw new PaymentExceptions.LimitExceeded(
                    "The smallest %s you can make is %s."
                            .formatted(intentType.name().toLowerCase(), money(limit.minAmount(), amount)));
        }
        if (amount.amount().compareTo(limit.maxAmount()) > 0) {
            throw new PaymentExceptions.LimitExceeded(
                    "The largest %s you can make is %s."
                            .formatted(intentType.name().toLowerCase(), money(limit.maxAmount(), amount)));
        }

        BigDecimal spent = spentToday(customerId, intentType, excludeIntentId);
        BigDecimal wouldBe = spent.add(amount.amount()).add(fee.amount());
        if (wouldBe.compareTo(limit.dailyMaxAmount()) > 0) {
            throw new PaymentExceptions.LimitExceeded(
                    "This would take today's %ss to %s, past your daily limit of %s."
                            .formatted(
                                    intentType.name().toLowerCase(),
                                    money(wouldBe, amount),
                                    money(limit.dailyMaxAmount(), amount)));
        }
    }

    /**
     * The band this payment has to fall inside, for a screen that wants to state
     * it rather than wait to refuse. Empty when no row is configured, which is
     * the state of a fresh database.
     */
    @Transactional(readOnly = true)
    Optional<TransactionLimits> limitsFor(
            IntentType intentType, PaymentChannel channel, String kycLevel, String currency) {
        return findLimit(intentType, channel, kycLevel, currency)
                .map(limit -> new TransactionLimits(
                        Money.of(limit.minAmount(), currency),
                        Money.of(limit.maxAmount(), currency),
                        Money.of(limit.dailyMaxAmount(), currency)));
    }

    private Optional<Limit> findLimit(IntentType intentType, PaymentChannel channel, String kycLevel, String currency) {
        return jdbcClient.sql(LIMIT_SQL)
                .param("intentType", intentType.name())
                .param("channel", channel.name())
                .param("kycLevel", kycLevel)
                .param("currency", currency)
                .query((rs, rowNum) -> new Limit(
                        rs.getBigDecimal("min_amount"),
                        rs.getBigDecimal("max_amount"),
                        rs.getBigDecimal("daily_max_amount")))
                .optional();
    }

    private BigDecimal spentToday(UUID customerId, IntentType intentType, UUID excludeIntentId) {
        return jdbcClient.sql(SPENT_TODAY_SQL)
                .param("customerId", customerId)
                .param("intentType", intentType.name())
                .param("excludeIntentId", excludeIntentId)
                .query(BigDecimal.class)
                .single();
    }

    private void warnOnce(IntentType intentType, PaymentChannel channel, String kycLevel) {
        String combination = "%s/%s/%s".formatted(intentType, channel, kycLevel);
        if (warnedAbout.add(combination)) {
            log.warn("No transaction_limit configured for {} — {} payments are running unlimited. "
                    + "Insert an approved row in payment.transaction_limit.", combination, combination);
        }
    }

    private static String money(BigDecimal value, Money like) {
        return Money.of(value, like.currency()).toString();
    }

    private record Limit(BigDecimal minAmount, BigDecimal maxAmount, BigDecimal dailyMaxAmount) {}
}
