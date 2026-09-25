package com.konexio.bank.shared.actor;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The current {@link Actor} for this thread. {@link ActorContextTransactionListener}
 * copies it into the PostgreSQL session at the start of every transaction, where
 * the history triggers read it through {@code common.current_actor_type()} and
 * friends — which is why callers never have to pass "who did this" down through
 * service signatures that have no other use for it.
 *
 * <p>Populated per request by {@code ActorContextFilter}; background jobs and
 * tests set it explicitly with {@link #call(Actor, Callable)} or {@link #run}.
 * Empty means the database falls back to {@code SYSTEM}.
 */
public final class ActorContext {

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private ActorContext() {}

    public static Optional<Actor> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void set(Actor actor) {
        CURRENT.set(actor);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code action} with {@code actor} in scope, restoring whatever was there before. */
    public static void run(Actor actor, Runnable action) {
        call(actor, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T call(Actor actor, Callable<T> action) {
        Actor previous = CURRENT.get();
        CURRENT.set(actor);
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Action failed while running as " + actor, e);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
