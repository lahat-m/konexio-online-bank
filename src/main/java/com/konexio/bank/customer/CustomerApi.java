package com.konexio.bank.customer;

import com.konexio.bank.customer.domain.CustomerService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * The customer module's public face. Other modules call this and nothing else
 * here — not the entity, not the repository, not the service in {@code domain}.
 */
@Component
public class CustomerApi {

    private final CustomerService customerService;

    CustomerApi(CustomerService customerService) {
        this.customerService = customerService;
    }

    public Optional<CustomerProfile> find(UUID customerId) {
        return customerService.find(customerId);
    }

    /**
     * Customer search for the staff console: part of a name, the end of a phone
     * number, or an email address. A blank term lists everyone.
     */
    public Page<CustomerProfile> search(String term, Pageable pageable) {
        return customerService.search(term, pageable);
    }

    /**
     * The profile for this customer, created from their credential if it does
     * not exist yet. Call this before anything that needs a customer to be on
     * the bank's books — opening an account, in particular, since
     * {@code account.account.customer_id} is a foreign key to the profile.
     *
     * @throws com.konexio.bank.shared.error.ResourceNotFoundException (404) when
     *     there is no credential for this id either
     */
    public CustomerProfile ensureProfile(UUID customerId) {
        return customerService.ensure(customerId);
    }
}
