package io.b2mash.maestro.admin.model;

import java.util.List;

/**
 * Simple pagination wrapper for admin dashboard queries.
 *
 * @param items  the page content
 * @param offset zero-based offset
 * @param limit  page size
 * @param total  total number of matching items across all pages
 * @param <T>    the item type
 */
public record Page<T>(
    List<T> items,
    int offset,
    int limit,
    long total
) {
    /** Returns the current page number (1-based). */
    public int pageNumber() {
        return limit <= 0 ? 1 : (offset / limit) + 1;
    }

    /** Returns the total number of pages. */
    public int totalPages() {
        return limit <= 0 ? 0 : (int) Math.ceil((double) total / limit);
    }

    /** Returns true if there is a next page. */
    public boolean hasNext() {
        return offset + limit < total;
    }

    /** Returns true if there is a previous page. */
    public boolean hasPrevious() {
        return offset > 0;
    }
}
