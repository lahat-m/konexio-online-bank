package com.konexio.bank.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Uuid7Test {

    @Test
    @DisplayName("generates version 7, variant 2 UUIDs — the layout PostgreSQL's uuidv7() produces")
    void generatesVersion7() {
        UUID id = Uuid7.generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("ids sort in generation order, which is what keeps index inserts at the right edge")
    void isTimeOrdered() throws InterruptedException {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(Uuid7.generate());
            Thread.sleep(2);
        }

        assertThat(ids).isSortedAccordingTo(Uuid7Test::compareUnsigned);
    }

    @Test
    @DisplayName("two ids from the same millisecond still differ")
    void isUnique() {
        assertThat(Uuid7.generate()).isNotEqualTo(Uuid7.generate());
    }

    /** {@code UUID.compareTo} compares signed longs, which is not the lexical order of the hex form. */
    private static int compareUnsigned(UUID left, UUID right) {
        int mostSignificant = Long.compareUnsigned(
                left.getMostSignificantBits(), right.getMostSignificantBits());
        return mostSignificant != 0
                ? mostSignificant
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
