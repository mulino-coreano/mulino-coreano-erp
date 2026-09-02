package com.mulinocoreano.backend.docs;

import com.mulinocoreano.backend.common.response.ApiResult;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

//404 에러만 따로 분리
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(
        responseCode = "404",
        description = "Cannot find resource",
        content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiResult.class)
        )
)
public @interface ApiNotFoundResponse {
}
