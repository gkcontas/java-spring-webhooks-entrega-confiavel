package com.gkcontas.webhooks.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String error, List<String> details) {

    public static ApiError of(String error) {
        return new ApiError(error, List.of());
    }
}
