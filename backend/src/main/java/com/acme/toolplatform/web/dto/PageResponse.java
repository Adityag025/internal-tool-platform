package com.acme.toolplatform.web.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A stable collection envelope.
 *
 * Returning a bare JSON array from a collection endpoint is a design dead-end:
 * the day you need paging or totals you have to break every client. An
 * envelope leaves room to grow without a breaking change.
 */
public record PageResponse<T>(List<T> data, PageMeta pagination) {

    public record PageMeta(int page, int size, long totalElements, int totalPages, boolean hasMore) {
    }

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                new PageMeta(
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalElements(),
                        page.getTotalPages(),
                        page.hasNext()));
    }
}
