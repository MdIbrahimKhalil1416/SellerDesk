package com.sellerdesk.common.dto;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a list endpoint (docs/ARCHITECTURE.md §5):
 * {@code {"content": [...], "page": 0, "size": 20, "totalElements": 135, "totalPages": 7}}.
 *
 * <p>Our own record instead of Spring Data's {@code Page}: Spring's JSON for {@code Page} is
 * large, changes between versions, and would tie the API format to a library.
 *
 * @param page 0-based page number
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public PageResponse {
        content = List.copyOf(content);
    }

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        return new PageResponse<>(content.stream().<R>map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
