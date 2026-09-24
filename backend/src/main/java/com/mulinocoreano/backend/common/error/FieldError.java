package com.mulinocoreano.backend.common.error;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Field-level validation error")
public record FieldError(
        @Schema(description = "Field name", example = "email")
        String field,
        @Schema(description = "Reason", example = "must be a well-formed email address")
        String reason
) {}