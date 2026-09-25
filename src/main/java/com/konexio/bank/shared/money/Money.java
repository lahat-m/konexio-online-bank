package com.konexio.bank.shared.money;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount in a single currency, serialized as
 * {@code {"amount": "3500.00", "currency": "KES"}} — the amount is a JSON
 * <em>string</em> so no precision is lost on the way to a client that parses
 * JSON numbers as doubles (see docs/rest-api.md "Data formats").
 *
 * <p>Always scale 2, matching {@code common.money numeric(19,2)}. Construction
 * never rounds: a value with more than two decimals is a bug in the caller, not
 * something to silently absorb, so it throws instead.
 *
 * <p>The two properties are pinned with {@link JsonIncludeProperties} because
 * Jackson treats every no-argument accessor as a property: without it,
 * {@link #isZero()} and {@link #isNegative()} ship as {@code "zero"} and
 * {@code "negative"} in every response that contains an amount, and a helper
 * added later would silently widen the wire format again.
 */
@JsonIncludeProperties({"amount", "currency"})
public record Money(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency) implements Comparable<Money> {

    public static final String KES = "KES";

    private static final int SCALE = 2;

    public Money {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code: " + currency);
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money kes(String amount) {
        return new Money(new BigDecimal(amount), KES);
    }

    public static Money kes(long amount) {
        return new Money(BigDecimal.valueOf(amount), KES);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        return new Money(amount.add(requireSameCurrency(other).amount), currency);
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(requireSameCurrency(other).amount), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    /** Compares amounts only; comparing across currencies is a programming error. */
    @Override
    public int compareTo(@NonNull Money other) {
        return amount.compareTo(requireSameCurrency(other).amount);
    }

    private Money requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: %s vs %s".formatted(currency, other.currency));
        }
        return other;
    }

    @Override
    public String toString() {
        return currency + " " + amount.toPlainString();
    }
}
