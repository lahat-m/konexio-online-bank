package com.konexio.bank.account.domain;

import com.konexio.bank.account.AccountStatus;
import com.konexio.bank.account.AccountType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The list query is spelled as four derived methods rather than one JPQL string
 * with {@code (:type is null or ...)} branches: the filters are optional on the
 * wire, and a query whose parameters may be null is a query whose plan and whose
 * parameter types depend on the request. Four named methods say what each
 * combination does and are checked when the context starts.
 */
interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByIdAndCustomerId(UUID id, UUID customerId);

    Optional<Account> findByAccountNumber(String accountNumber);

    Optional<Account> findByAccountClassAndAccountTypeAndCurrency(
            AccountClass accountClass, AccountType accountType, String currency);

    Page<Account> findByCustomerId(UUID customerId, Pageable pageable);

    Page<Account> findByCustomerIdAndAccountType(UUID customerId, AccountType accountType, Pageable pageable);

    Page<Account> findByCustomerIdAndStatus(UUID customerId, AccountStatus status, Pageable pageable);

    Page<Account> findByCustomerIdAndAccountTypeAndStatus(
            UUID customerId, AccountType accountType, AccountStatus status, Pageable pageable);

    Optional<Account> findByCustomerIdAndAccountTypeAndStatusNot(
            UUID customerId, AccountType accountType, AccountStatus status);

    /** Open = anything but CLOSED, matching the {@code uq_account_one_open_main} partial index. */
    long countByCustomerIdAndAccountTypeAndStatusNot(UUID customerId, AccountType accountType, AccountStatus status);

    /**
     * Resets the dormancy clock, and wakes a dormant account, as one statement
     * rather than by loading the entity and changing it.
     *
     * <p>It has to be a statement. This is called straight after the ledger has
     * posted, and {@code ledger.apply_posting()} increments {@code version} in
     * the database — deliberately, as its own comment says, "invalidates stale
     * optimistic-locked entities in the app". Any {@code Account} already in the
     * persistence context is therefore a version behind, and saving it would
     * update nothing and fail the optimistic-lock check. Touching the two columns
     * directly sidesteps a conflict that is not a conflict: nobody is competing
     * over the dormancy clock.
     *
     * <p>Bypasses the persistence context, so an {@code Account} loaded earlier in
     * the same transaction is stale afterwards. Nothing mutates one after this.
     */
    @Modifying
    @Query(value = """
            update account.account
               set last_customer_activity_at = now(),
                   status        = case when status = 'DORMANT' then 'ACTIVE' else status end,
                   dormant_since = case when status = 'DORMANT' then null else dormant_since end,
                   version       = version + 1
             where id = :accountId
            """, nativeQuery = true)
    int touchCustomerActivity(@Param("accountId") UUID accountId);
}
