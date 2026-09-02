package com.mulinocoreano.backend.docs;

import com.mulinocoreano.backend.common.response.ApiResult;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

//대부분 API에서 공통적으로 발생 가능한 응답 묶음
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
        @ApiResponse(
                responseCode = "400",
                description = "Bad Request Error",
                content = @Content(
                        mediaType = "application/json",
                        schema = @Schema(
                                implementation = ApiResult.class
                        )
                )
        ),
        @ApiResponse(
                responseCode = "500",
                description = "Server Internal Error",
                content = @Content(
                        mediaType = "application/json",
                        schema = @Schema(implementation = ApiResult.class)
                )
        )
})
public @interface ApiCommonErrorResponses {
}
