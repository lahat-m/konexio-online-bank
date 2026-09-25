package com.konexio.bank.loan.domain;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Products and what they currently cost.
 *
 * <p>{@link JdbcClient} rather than JPA: both tables are read-only to this
 * application. {@code R__konexio_grants.sql} revokes INSERT, UPDATE and DELETE
 * on them, because a price is a decision somebody signs off in a migration —
 * {@code approved_by} is a NOT NULL column for that reason. Mapping them as
 * entities would model a write path the database refuses.
 *
 * <p>The join is on {@code valid_during @> current_date}, and the temporal
 * primary key guarantees at most one price matches on any given day.
 */
@Component
class LoanProductStore {

    private static final String PRICED_PRODUCTS_SQL = """
            select p.id, p.code, p.name, p.principal, p.currency, p.term_days,
                   extract(epoch from p.offer_ttl) as offer_ttl_seconds,
                   r.interest_rate, r.processing_fee
              from loan.loan_product p
              join loan.loan_product_pricing r on r.product_id = p.id
             where p.status = 'ACTIVE'
               and r.valid_during @> current_date
             order by p.principal
            """;

    private final JdbcClient jdbcClient;

    LoanProductStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Every product a customer could be offered right now.
     *
     * <p>Empty when no price is in force today. {@code INSTANT_10K} is priced by
     * V20; any other product has to have its pricing approved in a migration of
     * its own before it appears here. An unpriced product is not an offer
     * waiting to happen; it is a product nobody has agreed terms for, so it is
     * simply not offered.
     */
    List<PricedProduct> pricedProducts() {
        return jdbcClient.sql(PRICED_PRODUCTS_SQL).query(LoanProductStore::map).list();
    }

    Optional<PricedProduct> findPriced(UUID productId) {
        return pricedProducts().stream().filter(product -> product.id().equals(productId)).findFirst();
    }

    private static PricedProduct map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PricedProduct(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBigDecimal("principal"),
                rs.getString("currency"),
                rs.getInt("term_days"),
                (long) rs.getDouble("offer_ttl_seconds"),
                rs.getBigDecimal("interest_rate"),
                rs.getBigDecimal("processing_fee"));
    }

    /**
     * @param interestRate a flat rate over the whole term, not per annum —
     *                     {@code 0.0500} on a 30-day product is 5% of the
     *                     principal, once
     * @param offerTtlSeconds how long an offer made from this product stands
     */
    record PricedProduct(
            UUID id,
            String code,
            String name,
            BigDecimal principal,
            String currency,
            int termDays,
            long offerTtlSeconds,
            BigDecimal interestRate,
            BigDecimal processingFee) {}
}
