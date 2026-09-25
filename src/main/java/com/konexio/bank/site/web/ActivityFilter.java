package com.konexio.bank.site.web;

import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.StatementDirection;
import java.util.EnumSet;
import java.util.Set;

/**
 * The chips along the top of screen 4.1, and what each one asks the ledger for.
 *
 * <p>An enum rather than free strings in the query, so an unknown {@code ?filter}
 * falls back to {@link #ALL} instead of reaching the ledger: this is a URL a
 * customer can edit, and the answer to a nonsense chip is the whole list, not an
 * error page.
 *
 * @param label what the chip says
 * @param types the kinds of entry it keeps, or null for every kind — "Loans" is
 *              two kinds, a disbursement coming in and a repayment going out
 */
enum ActivityFilter {

    ALL("all", "All", null, null),
    IN("in", "Money in", StatementDirection.IN, null),
    OUT("out", "Money out", StatementDirection.OUT, null),
    LOANS("loans", "Loans", null, EnumSet.of(EntryType.LOAN_DISBURSEMENT, EntryType.LOAN_REPAYMENT));

    private final String slug;
    private final String label;
    private final StatementDirection direction;
    private final Set<EntryType> types;

    ActivityFilter(String slug, String label, StatementDirection direction, Set<EntryType> types) {
        this.slug = slug;
        this.label = label;
        this.direction = direction;
        this.types = types;
    }

    /**
     * One chip, ready to draw. A view record rather than the enum itself: a
     * template reaches a record's accessors, and an enum's plain methods it does
     * not — and the chosen one is a fact about this page, not about the filter.
     */
    record Chip(String slug, String label, boolean chosen) {}

    Chip chip(ActivityFilter chosen) {
        return new Chip(slug, label, this == chosen);
    }

    static ActivityFilter of(String slug) {
        if (slug != null) {
            for (ActivityFilter filter : values()) {
                if (filter.slug.equalsIgnoreCase(slug)) {
                    return filter;
                }
            }
        }
        return ALL;
    }

    String slug() {
        return slug;
    }

    String label() {
        return label;
    }

    StatementDirection direction() {
        return direction;
    }

    Set<EntryType> types() {
        return types;
    }
}
