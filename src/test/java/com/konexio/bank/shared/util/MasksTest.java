package com.konexio.bank.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MasksTest {

    @Test
    @DisplayName("a masked phone keeps the country code and the last three digits only")
    void masksPhone() {
        assertThat(Masks.phone("+254712345312")).isEqualTo("+254 7•• ••• 312");
        assertThat(Masks.phone("+254712345312")).doesNotContain("12345");
        assertThat(Masks.phone(null)).isNull();
    }

    @Test
    @DisplayName("a masked name is recognisable but not harvestable")
    void masksName() {
        assertThat(Masks.name("JOSEPH OTIENO")).isEqualTo("JOSEPH OT****");
        assertThat(Masks.name("PRINCE")).isEqualTo("PR****");
    }

    @Test
    @DisplayName("an account number shows only its last four digits")
    void masksAccountNumber() {
        assertThat(Masks.accountNumber("100277814420")).isEqualTo("••••4420");
    }
}
