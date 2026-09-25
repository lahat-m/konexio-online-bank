package com.konexio.bank.shared.request;

/**
 * Holds the current request's {@link ClientRequest} for this thread, so code
 * several layers below any {@code HttpServletRequest} — the security-event
 * recorder and the audit writer, called from dozens of places — can record the
 * caller's IP and device without threading them through every signature.
 *
 * <p>Off-request threads (scheduled jobs, {@code @Async}) see
 * {@link ClientRequest#UNKNOWN}, which is the correct "no request to attribute
 * this to" answer.
 */
public final class ClientRequestContext {

    private static final ThreadLocal<ClientRequest> CURRENT = new ThreadLocal<>();

    private ClientRequestContext() {}

    public static ClientRequest get() {
        ClientRequest current = CURRENT.get();
        return current == null ? ClientRequest.UNKNOWN : current;
    }

    static void set(ClientRequest request) {
        CURRENT.set(request);
    }

    static void clear() {
        CURRENT.remove();
    }
}
