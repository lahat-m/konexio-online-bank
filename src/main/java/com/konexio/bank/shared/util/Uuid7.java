package com.konexio.bank.shared.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates RFC 9562 version 7 UUIDs — 48 bits of Unix milliseconds followed by
 * random bits — matching PostgreSQL 18's {@code uuidv7()}.
 *
 * <p>Needed wherever the application, rather than a column default, has to
 * produce an identifier it also wants time-ordered: {@code customer_credential.customer_id}
 * is generated here so the value is known before the insert and can be carried
 * straight into the JWT {@code sub} and the {@code CustomerRegistered} event.
 * Entity primary keys use Hibernate's {@code @UuidGenerator(style = VERSION_7)},
 * which produces the same layout.
 *
 * <p>Java's {@code UUID.randomUUID()} is version 4: unordered, so as a primary
 * key it scatters inserts across the whole B-tree instead of appending to its
 * right edge.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {}

    public static UUID generate() {
        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);
        long timestamp = System.currentTimeMillis();

        // 48 bits timestamp | 4 bits version (7) | 12 bits random
        long mostSignificantBits = (timestamp << 16)
                | (0x7L << 12)
                | (((long) randomBytes[0] & 0x0F) << 8)
                | ((long) randomBytes[1] & 0xFF);

        // 2 bits variant (0b10) | 62 bits random
        long leastSignificantBits = 0;
        for (int i = 2; i < 10; i++)
            leastSignificantBits = (leastSignificantBits << 8) | ((long) randomBytes[i] & 0xFF);
        leastSignificantBits = (leastSignificantBits & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
