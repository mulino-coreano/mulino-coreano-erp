package com.mulinocoreano.backend.docs;

import jakarta.servlet.Filter;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 기본 OpenAPI 경로 /v3/api-docs를 springdoc 문서 경로(/api-docs)로 servlet forward한다.
 * Spring MVC handler로 매핑하면 springdoc이 같은 접미사의 handler를 path prefix로 읽어
 * servers와 Swagger UI configUrl이 기동마다 달라지므로 handler mapping을 만들지 않는다.
 * 보안 filter 뒤에서 REQUEST dispatch만 받으므로 GET 허용 규칙은 그대로 적용된다.
 */
@Configuration
public class OpenApiDocumentAliasConfiguration {
    private static final String DEFAULT_DOCUMENT_PATH = "/v3/api-docs";

    @Bean
    FilterRegistrationBean<Filter> openApiDefaultPathAlias(SpringDocConfigProperties springdoc) {
        String documentPath = springdoc.getApiDocs().getPath();
        var alias = new FilterRegistrationBean<Filter>((request, response, chain) ->
                request.getRequestDispatcher(documentPath).forward(request, response));
        alias.setName("openApiDefaultPathAlias");
        alias.addUrlPatterns(DEFAULT_DOCUMENT_PATH);
        return alias;
    }
}
