package com.konexio.bank.notification.domain;

/**
 * One pass of the relay.
 *
 * <p>An interface so the scheduler, which lives in {@code config}, can drive the
 * relay without the relay itself having to be public to the whole application.
 * Tests use it for the same reason they call the jobs directly: waiting two
 * seconds for a timer is a worse test than asking for a pass.
 */
public interface OutboxRelayRunner {

    /** @return how many events this pass dealt with */
    int drain();
}
