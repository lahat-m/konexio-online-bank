package com.konexio.bank.apisecurity;

import com.konexio.bank.apisecurity.domain.IdempotencyService;
import org.springframework.stereotype.Component;

/**
 * The api_security module's one outward-facing operation: give the idempotency
 * table a bottom.
 *
 * <p>Separate from the guards because it is for the jobs module rather than for
 * a request, and because nothing else about idempotency is any other module's
 * business — the filter needs no API at all.
 */
@Component
public class IdempotencyMaintenance {

    private final IdempotencyService idempotency;

    IdempotencyMaintenance(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    public int purgeExpiredKeys() {
        return idempotency.purgeExpired();
    }
}
