package com.konexio.bank.apisecurity.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param idempotency which requests must carry an {@code Idempotency-Key}, and
 *                    whether the check runs at all
 */
@ConfigurationProperties(prefix = "app.api-security")
public record ApiSecurityProperties(@DefaultValue IdempotencySettings idempotency) {

    /**
     * @param enabled turning this off disables replay protection on endpoints
     *                that move money. It exists for a local experiment, not for
     *                an environment with customers in it.
     * @param paths   the path patterns a state-changing request must carry a key
     *                for: creating and confirming a payment, opening an account,
     *                accepting a loan. Exactly the requests the header table in
     *                docs/rest-api.md §1 names, and no more — editing a pending
     *                quote or cancelling one moves no money, and demanding a key
     *                there would be a contract this API does not have.
     */
    public record IdempotencySettings(
            @DefaultValue("true") boolean enabled,
            @DefaultValue({
                "/api/accounts",
                "/api/deposits",
                "/api/deposits/*/confirmation",
                "/api/withdrawals",
                "/api/withdrawals/*/confirmation",
                "/api/transfers",
                "/api/transfers/*/confirmation",
                "/api/loans"
            })
            List<String> paths) {}
}
