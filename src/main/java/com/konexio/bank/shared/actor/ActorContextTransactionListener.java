package com.konexio.bank.shared.actor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Copies {@link ActorContext} into the PostgreSQL session as soon as a
 * transaction begins, so every row written in it is attributed to the right
 * actor by the history triggers.
 *
 * <p>It has to happen here rather than in a filter or an aspect: {@code SET
 * LOCAL} lives for exactly one transaction on exactly one connection, so the
 * settings must be written after {@code BEGIN} and before the first statement
 * of that same transaction. {@code afterBegin} is precisely that point, and
 * Spring Boot registers any {@code TransactionExecutionListener} bean on the
 * transaction manager for us.
 *
 * <p>{@code set_config(..., true)} rather than {@code SET LOCAL} because the
 * former takes bind parameters — {@code SET LOCAL} only accepts literals, which
 * would mean string-concatenating an actor id into SQL.
 *
 * <p>No actor in scope means no statement is issued at all: unauthenticated
 * reads pay nothing, and {@code common.current_actor_type()} already defaults to
 * {@code SYSTEM}.
 */
@Component
class ActorContextTransactionListener implements TransactionExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ActorContextTransactionListener.class);

    private static final String APPLY_ACTOR_SQL = """
            select set_config('konexio.actor_type', ?1, true),
                   set_config('konexio.actor_id', ?2, true),
                   set_config('konexio.change_reason', ?3, true)
            """;

    private final EntityManagerFactory entityManagerFactory;

    ActorContextTransactionListener(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure != null) {
            return;
        }
        Actor actor = ActorContext.current().orElse(null);
        if (actor == null) {
            return;
        }
        EntityManager entityManager =
                EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (entityManager == null) {
            // A transaction on some other resource manager — nothing to set here.
            return;
        }
        try {
            entityManager.createNativeQuery(APPLY_ACTOR_SQL)
                    .setParameter(1, actor.type().name())
                    .setParameter(2, actor.id() == null ? "" : actor.id().toString())
                    .setParameter(3, actor.changeReason() == null ? "" : actor.changeReason())
                    .getSingleResult();
        } catch (RuntimeException e) {
            // Losing the actor context must not fail the business operation; the
            // history triggers fall back to SYSTEM and this is loud enough to notice.
            log.warn("Could not apply actor context {} to the database session", actor, e);
        }
    }
}
