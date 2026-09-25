package com.konexio.bank.customer.domain;

import com.konexio.bank.shared.error.ResourceNotFoundException;

/** The failures the customer flows can produce. */
final class CustomerExceptions {

    private CustomerExceptions() {}

    /**
     * No credential behind this customer id. Reachable only with a token signed
     * for a customer whose credential has since been deleted, which is why the
     * message says nothing about why the id is unknown.
     */
    static final class UnknownCustomer extends ResourceNotFoundException {
        UnknownCustomer() {
            super("customer-not-found", "Customer not found", "No customer profile for this id.");
        }
    }
}
