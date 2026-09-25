package com.konexio.bank.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MoneyTest {

    @Test
    @DisplayName("amounts are held at the scale of the money column")
    void normalisesScale() {
        assertThat(Money.kes("3500").amount()).isEqualTo(new BigDecimal("3500.00"));
        assertThat(Money.kes(10_000).toString()).isEqualTo("KES 10000.00");
    }

    @Test
    @DisplayName("a third decimal is a caller bug, not something to round away")
    void refusesToRound() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("10.005"), "KES"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("arithmetic across currencies is rejected")
    void refusesMixedCurrencies() {
        assertThatThrownBy(() -> Money.kes("100").plus(Money.of(new BigDecimal("100"), "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("adds, subtracts and compares within a currency")
    void doesArithmetic() {
        Money balance = Money.kes("24500.00");

        assertThat(balance.minus(Money.kes("3500.00"))).isEqualTo(Money.kes("21000.00"));
        assertThat(balance.plus(Money.kes("0.50")).amount()).isEqualTo(new BigDecimal("24500.50"));
        assertThat(balance.isGreaterThan(Money.kes("30000.00"))).isFalse();
        assertThat(Money.zero("KES").isZero()).isTrue();
    }

    @Test
    @DisplayName("a currency code must be three upper-case letters")
    void validatesCurrency() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "kes"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the wire format is exactly two fields, with the amount as a string")
    void serialisesToTheDocumentedShape() {
        // Jackson exposes every no-argument accessor, so isZero() and isNegative()
        // would otherwise appear in every response carrying an amount.
        assertThat(new ObjectMapper().writeValueAsString(Money.kes("3500")))
                .isEqualTo("{\"amount\":\"3500.00\",\"currency\":\"KES\"}");
    }
}
