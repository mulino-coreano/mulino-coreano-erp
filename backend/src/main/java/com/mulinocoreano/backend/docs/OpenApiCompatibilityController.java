package com.mulinocoreano.backend.docs;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기존 /api-docs와 기본 OpenAPI 경로를 함께 제공한다. */
@RestController
public class OpenApiCompatibilityController {
    private final OpenApiWebMvcResource resource;
    public OpenApiCompatibilityController(OpenApiWebMvcResource resource) { this.resource = resource; }
    @GetMapping(value = "/v3/api-docs", produces = "application/json")
    public byte[] apiDocs(HttpServletRequest request, Locale locale) throws Exception {
        return resource.openapiJson(request, "/api-docs", locale);
    }
}
