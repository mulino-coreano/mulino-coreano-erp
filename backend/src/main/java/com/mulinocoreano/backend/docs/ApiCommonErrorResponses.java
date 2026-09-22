package com.mulinocoreano.backend.docs;

import com.mulinocoreano.backend.common.error.ApiError;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Bad Request",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "404", description = "Not Found",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "500", description = "Internal Server Error",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = ApiError.class)))
})
public @interface ApiCommonErrorResponses {
}