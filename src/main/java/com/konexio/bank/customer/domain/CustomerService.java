package com.konexio.bank.customer.domain;

import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.identity.CustomerIdentity;
import com.konexio.bank.identity.CustomerRegistered;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.audit.AuditResource;
import com.konexio.bank.shared.audit.AuditWriter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and reads banking-side customer profiles.
 *
 * <p>A profile is derived data: every field starts as a copy of what the
 * identity module verified at sign-up. That is why it can always be rebuilt
 * from the credential, and why {@link #ensure(UUID)} is safe to call on any
 * path that needs a profile to exist — it introduces no information the bank
 * does not already hold.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final IdentityApi identityApi;
    private final AuditWriter auditWriter;

    CustomerService(CustomerRepository customers, IdentityApi identityApi, AuditWriter auditWriter) {
        this.customers = customers;
        this.identityApi = identityApi;
        this.auditWriter = auditWriter;
    }

    @Transactional(readOnly = true)
    public Optional<CustomerProfile> find(UUID customerId) {
        return customers.findById(customerId).map(CustomerService::toProfile);
    }

    /**
     * Customers matching a search term, or every customer when it is blank.
     *
     * <p>Two paths rather than one query with an "or the term is empty" branch:
     * an empty search is a different question — "show me the book" — and a
     * predicate that is sometimes a no-op is a predicate the planner has to
     * guess about.
     */
    @Transactional(readOnly = true)
    public Page<CustomerProfile> search(String term, Pageable pageable) {
        Page<Customer> page = term == null || term.isBlank()
                ? customers.findAll(pageable)
                : customers.search(term.trim(), pageable);
        return page.map(CustomerService::toProfile);
    }

    /**
     * Returns the profile, creating it from the credential if it is missing.
     *
     * <p>The normal path is {@link CustomerProvisioner}, which creates the
     * profile as part of the sign-up that produced the credential. This is the
     * repair path: it covers customers who registered before this module
     * existed, and anything that leaves a credential without a profile. Opening
     * an account and reading the dashboard both go through it, which is what the
     * schema means by "created when registration completes or on the first
     * {@code POST /api/accounts}".
     *
     * @throws CustomerExceptions.UnknownCustomer when no credential exists either
     */
    @Transactional
    public CustomerProfile ensure(UUID customerId) {
        return customers.findById(customerId)
                .map(CustomerService::toProfile)
                .orElseGet(() -> rebuildFromCredential(customerId));
    }

    /** Called by the sign-up flow's event, inside the transaction that created the credential. */
    @Transactional
    public void createFrom(CustomerRegistered event) {
        if (customers.existsById(event.customerId())) {
            return;
        }
        customers.save(new Customer(
                event.customerId(),
                event.fullName(),
                event.phone(),
                event.email(),
                KycLevel.parse(event.kycLevel())));
        auditWriter.record("CUSTOMER_PROFILE_CREATED", AuditResource.CUSTOMER, event.customerId(),
                AuditOutcome.SUCCESS, Map.of("source", "REGISTRATION"));
    }

    private CustomerProfile rebuildFromCredential(UUID customerId) {
        CustomerIdentity identity = identityApi.findCustomer(customerId)
                .orElseThrow(CustomerExceptions.UnknownCustomer::new);
        Customer customer = customers.saveAndFlush(new Customer(
                identity.customerId(),
                identity.fullName(),
                identity.phone(),
                identity.email(),
                KycLevel.parse(identity.kycLevel())));
        auditWriter.record("CUSTOMER_PROFILE_CREATED", AuditResource.CUSTOMER, customerId,
                AuditOutcome.SUCCESS, Map.of("source", "BACKFILL"));
        return toProfile(customer);
    }

    private static CustomerProfile toProfile(Customer customer) {
        return new CustomerProfile(
                customer.getId(),
                customer.getFullName(),
                customer.getPhone(),
                customer.getEmail(),
                customer.getKycLevel().name(),
                customer.getStatus().name(),
                customer.getCreatedAt());
    }
}
