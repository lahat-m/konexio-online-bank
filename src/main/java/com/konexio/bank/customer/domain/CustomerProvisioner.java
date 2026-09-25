package com.konexio.bank.customer.domain;

import com.konexio.bank.identity.CustomerRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates the banking-side profile the moment a sign-up produces a credential.
 *
 * <p>Deliberately a plain {@code @EventListener}, so it runs inside the
 * transaction that created the credential rather than after it commits. Two
 * reasons:
 *
 * <ul>
 *   <li>{@code customer.customer.id} is a foreign key to
 *       {@code identity.customer_credential.customer_id}. The profile is not an
 *       independent aggregate reacting to news from another module; it is the
 *       other half of the same fact, and the database already refuses to let the
 *       two exist apart. Committing the credential first only creates a window
 *       in which a customer exists and cannot be looked up.
 *   <li>After-commit delivery ({@code @ApplicationModuleListener}) needs
 *       Spring Modulith's {@code event_publication} table to survive a crash
 *       between the commit and the listener. That table arrives with the outbox
 *       in Phase 9; until then, an after-commit listener would be a silent
 *       at-most-once delivery, which is worse than a shared transaction.
 * </ul>
 *
 * <p>When the outbox lands, this can become an {@code @ApplicationModuleListener}
 * without touching anything else — {@link CustomerService#ensure} already covers
 * the window such a change would open.
 */
@Component
class CustomerProvisioner {

    private final CustomerService customerService;

    CustomerProvisioner(CustomerService customerService) {
        this.customerService = customerService;
    }

    @EventListener
    void on(CustomerRegistered event) {
        customerService.createFrom(event);
    }
}
