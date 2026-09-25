package com.konexio.bank.shared.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The envelope every list endpoint returns (docs/rest-api.md §1).
 *
 * <p>{@code pageNumber} is 1-based on the wire while Spring Data is 0-based, so
 * {@link #from(Page)} is the only place that translation happens.
 */
public record PagedResult<T>(
        List<T> data,
        long totalElements,
        int pageNumber,
        int totalPages,
        @JsonProperty("isFirst") boolean isFirst,
        @JsonProperty("isLast") boolean isLast,
        boolean hasNext,
        boolean hasPrevious) {

    public static <T> PagedResult<T> from(Page<T> page) {
        return new PagedResult<>(
                page.getContent(),
                page.getTotalElements(),
                page.getNumber() + 1,
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.hasNext(),
                page.hasPrevious());
    }

    /** Maps the entities of a page to DTOs without repeating the envelope translation. */
    public static <E, T> PagedResult<T> from(Page<E> page, Function<E, T> mapper) {
        return from(page.map(mapper));
    }
}
