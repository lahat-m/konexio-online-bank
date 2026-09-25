package com.konexio.bank.customer.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /**
     * The staff console's customer search: a name fragment, the tail of a phone
     * number, or an exact email address.
     *
     * <p>Three predicates rather than three endpoints, because the person typing
     * has one box and does not know which of the three they are holding. The
     * name half is what {@code ix_customer_full_name_trgm} exists for; the phone
     * half is an anchored suffix match, so "0712345678", "712345678" and the
     * full {@code +254712345678} all find the same customer.
     */
    @Query("""
            select c from Customer c
             where lower(c.fullName) like lower(concat('%', :term, '%'))
                or c.phone like concat('%', :term)
                or lower(c.email) = lower(:term)
            """)
    Page<Customer> search(@Param("term") String term, Pageable pageable);
}
