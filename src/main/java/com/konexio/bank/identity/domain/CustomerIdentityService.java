package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.CustomerIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of a credential, projected for other modules — never the entity. */
@Service
public class CustomerIdentityService {

    private final CustomerCredentialRepository credentials;

    CustomerIdentityService(CustomerCredentialRepository credentials) {
        this.credentials = credentials;
    }

    @Transactional(readOnly = true)
    public Optional<CustomerIdentity> find(UUID customerId) {
        return credentials.findByCustomerId(customerId).map(CustomerIdentityService::toView);
    }

    private static CustomerIdentity toView(CustomerCredential credential) {
        return new CustomerIdentity(
                credential.getCustomerId(),
                credential.getFullName(),
                credential.getPhone(),
                credential.getEmail(),
                credential.getKycLevel().name(),
                credential.getStatus().name());
    }
}
