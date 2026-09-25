package com.konexio.bank.identity.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.konexio.bank.shared.error.UnprocessableEntityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PinPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"1111", "0000", "1234", "4321", "123", "12345", "12a4", ""})
    @DisplayName("repeated digits, runs and anything that is not four digits are rejected")
    void rejectsWeakPins(String pin) {
        assertThatThrownBy(() -> PinPolicy.validate(pin, 4))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2483", "9042", "1357", "1224"})
    @DisplayName("an ordinary PIN is accepted")
    void acceptsReasonablePins(String pin) {
        assertThatCode(() -> PinPolicy.validate(pin, 4)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null PIN is rejected rather than reaching the encoder")
    void rejectsNull() {
        assertThatThrownBy(() -> PinPolicy.validate(null, 4))
                .isInstanceOf(UnprocessableEntityException.class);
    }
}
