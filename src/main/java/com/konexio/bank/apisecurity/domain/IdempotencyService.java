package com.konexio.bank.apisecurity.domain;

import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.shared.util.Digests;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a request runs or replays.
 *
 * <p>Every method here is {@code REQUIRES_NEW}, which is the whole point:
 * claiming a key has to be <em>committed</em> before the handler runs, or a
 * second request arriving a millisecond later would see nothing and run the same
 * transfer again. For the same reason the claim cannot join the handler's
 * transaction — it has to outlive a rollback, so that a failure is recorded as a
 * released key rather than vanishing along with the row that would have
 * prevented the replay.
 */
@Service
public class IdempotencyService {

    private final IdempotencyStore store;
    private final CustomerApi customerApi;

    IdempotencyService(IdempotencyStore store, CustomerApi customerApi) {
        this.store = store;
        this.customerApi = customerApi;
    }

    /**
     * Claims the key for this request, or reports what the first request holding
     * it did.
     *
     * @param requestPath path and query string together: the closure endpoint
     *                    carries its reason as a query parameter, and the same
     *                    key on {@code ?reason=NOT_USED} and {@code ?reason=OTHER}
     *                    is two different requests
     * @throws ApiSecurityExceptions.IdempotencyKeyReused when the key was used
     *     for a different method, path or body
     * @throws ApiSecurityExceptions.RequestInProgress when the first request
     *     with this key has not answered yet
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyOutcome begin(
            UUID customerId, UUID key, String httpMethod, String requestPath, byte[] body) {
        // customer_id is a foreign key to the banking-side profile, which a
        // customer registered before that module existed may not have yet.
        customerApi.ensureProfile(customerId);

        byte[] requestHash = Digests.sha256(body);
        if (store.claim(customerId, key, httpMethod, requestPath, requestHash)) {
            return new IdempotencyOutcome.Proceed();
        }

        IdempotencyRecord existing = store.find(customerId, key)
                // Claimed by someone between the insert and this read, then
                // released when their request failed. Their retry and this one
                // are both free to proceed; the handler's own rules decide.
                .orElse(null);
        if (existing == null) {
            return new IdempotencyOutcome.Proceed();
        }
        if (!existing.httpMethod().equals(httpMethod)
                || !existing.requestPath().equals(requestPath)
                || !Digests.matches(existing.requestHash(), requestHash)) {
            throw new ApiSecurityExceptions.IdempotencyKeyReused();
        }
        if (!existing.completed()) {
            throw new ApiSecurityExceptions.RequestInProgress();
        }
        return new IdempotencyOutcome.Replay(existing.responseStatus(), existing.headers(), existing.body());
    }

    /**
     * Records what the handler answered, so the next identical request replays
     * it instead of running.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID customerId, UUID key, int status, String headers, String body) {
        store.complete(customerId, key, status, headers, body);
    }

    /**
     * Gives the key back after a failed request.
     *
     * <p>Only successful responses are worth replaying. A 422 for insufficient
     * funds says something about the account at one moment, not about the
     * request, and storing it would mean a customer who tops up and retries with
     * the same key gets the old refusal back forever. A 500 is worse: it says
     * nothing at all.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID customerId, UUID key) {
        store.release(customerId, key);
    }

    @Transactional
    public int purgeExpired() {
        return store.deleteExpired();
    }
}
