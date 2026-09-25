package com.konexio.bank.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * The {@code created_at} / {@code updated_at} / {@code version} trio every
 * mutable table in this schema carries.
 *
 * <p>Both timestamps are written by PostgreSQL — a {@code DEFAULT now()} on
 * insert and the {@code common.set_updated_at()} trigger on update — so they are
 * mapped as {@link Generated} and read back rather than set here. That keeps one
 * clock (the database's) authoritative for every writer, including migrations
 * and operator scripts, instead of letting an application server's clock skew
 * produce rows that appear to have been updated before they were created.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
