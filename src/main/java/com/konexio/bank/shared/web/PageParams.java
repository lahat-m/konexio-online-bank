package com.konexio.bank.shared.web;

import com.konexio.bank.shared.error.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Translates the 1-based {@code page} / {@code size} query parameters of the
 * REST contract into a Spring Data {@link Pageable}. Kept here so every list
 * endpoint applies the same defaults and the same upper bound on page size.
 */
public final class PageParams {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 100;

    private PageParams() {}

    public static Pageable of(int page, int size) {
        return of(page, size, Sort.unsorted());
    }

    public static Pageable of(int page, int size, Sort sort) {
        if (page < 1) {
            throw new ValidationException("page must be 1 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_SIZE);
        }
        return PageRequest.of(page - 1, size, sort);
    }
}
