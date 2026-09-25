package com.konexio.bank.shared.request;

/**
 * What the transport layer knows about the caller of the current request:
 * recorded on security events and audit rows, never used for authorization.
 *
 * @param ipAddress client IP, as resolved by {@link ClientRequestFilter}
 * @param userAgent raw {@code User-Agent} header, or null
 * @param deviceId  the mobile app's {@code Device-Id} header, or null for web callers
 */
public record ClientRequest(String ipAddress, String userAgent, String deviceId) {

    public static final ClientRequest UNKNOWN = new ClientRequest(null, null, null);
}
