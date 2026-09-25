package com.konexio.bank.shared.events;

/**
 * Marker for events published across module boundaries. Publishers use a plain
 * {@code ApplicationEventPublisher}.
 *
 * <p>Listeners are synchronous until Phase 9. {@code @ApplicationModuleListener}
 * delivers after the publishing transaction commits — which is what you want for
 * a slow or failing side effect that must not roll back the business change that
 * produced it — but it only keeps that promise when Spring Modulith's
 * {@code event_publication} table exists to hold the event across a crash
 * between the commit and the delivery. That table arrives with the outbox
 * (docs/implementation-order.md, Phase 9). Until then an after-commit listener
 * would be at-most-once delivery wearing an at-least-once annotation, so
 * cross-module listeners run in the publisher's transaction and say why.
 */
public interface DomainEvent {}
