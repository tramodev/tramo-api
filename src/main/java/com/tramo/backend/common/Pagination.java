package com.tramo.backend.common;

import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import org.springframework.data.domain.PageRequest;

public final class Pagination {
    private Pagination() {}

    public static PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || ((long) page + 1) * size > Integer.MAX_VALUE) {
            throw new RequestValidationException(RequestErrorCode.PAGINATION_INVALID);
        }
        return PageRequest.of(page, size);
    }
}
