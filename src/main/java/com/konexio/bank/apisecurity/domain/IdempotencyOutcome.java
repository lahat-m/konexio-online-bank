package com.konexio.bank.apisecurity.domain;

/**
 * What the filter should do with a request that carries an idempotency key.
 *
 * <p>Sealed and exhaustive so a new case cannot be added without every caller
 * being made to handle it — the alternative, a boolean plus a nullable stored
 * response, is the shape where "we already answered this" quietly becomes "run
 * it again".
 */
public sealed interface IdempotencyOutcome {

    /** The key is this request's. Run the handler, then record what it answered. */
    record Proceed() implements IdempotencyOutcome {}

    /**
     * This exact request already completed. Its answer is below; the handler
     * must not run.
     *
     * @param headers the replayed headers as JSON, or null when there were none
     * @param body    the recorded response body as JSON, or null when it was empty
     */
    record Replay(int status, String headers, String body) implements IdempotencyOutcome {}
}
