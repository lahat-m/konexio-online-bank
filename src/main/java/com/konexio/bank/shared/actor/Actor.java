package com.konexio.bank.shared.actor;

import java.util.UUID;

/**
 * Who is making the current change, and why.
 *
 * @param type         actor category, mirroring {@code common.actor_type}
 * @param id           customer, staff or provider id; null for {@link ActorType#SYSTEM}
 * @param changeReason optional free-text reason recorded by the history triggers,
 *                     e.g. {@code CUSTOMER_REQUEST} or {@code DORMANCY_SCAN}
 */
public record Actor(ActorType type, UUID id, String changeReason) {

    public static Actor customer(UUID customerId) {
        return new Actor(ActorType.CUSTOMER, customerId, null);
    }

    public static Actor staff(UUID staffId) {
        return new Actor(ActorType.STAFF, staffId, null);
    }

    public static Actor system(String changeReason) {
        return new Actor(ActorType.SYSTEM, null, changeReason);
    }

    public Actor because(String reason) {
        return new Actor(type, id, reason);
    }
}
