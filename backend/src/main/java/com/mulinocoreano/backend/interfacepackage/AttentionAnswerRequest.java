package com.mulinocoreano.backend.interfacepackage;

import jakarta.validation.constraints.*;

public record AttentionAnswerRequest(
        @NotBlank @Size(max = 8000) String answer,
        @NotNull @Positive Integer expectedVersion,
        @NotNull Scope scope) {
    public enum Scope {
        THIS_ACTION,
        THIS_CASE
    }
}
