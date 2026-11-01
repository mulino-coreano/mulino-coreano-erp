package com.mulinocoreano.backend.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST API 문서 계약: 기존 /api-docs, 기본 /v3/api-docs, Swagger UI가 실제 서블릿
 * 컨테이너에서 같은 서버 주소의 같은 문서를 가리킨다. MockMvc는 forward를 따라가지 않아
 * 실제 포트로 요청한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentAliasIntegrationTest {
    private static final Pattern CONFIG_URL = Pattern.compile("\"configUrl\"\\s*:\\s*\"([^\"]+)\"");
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper mapper;

    @Test
    void legacyAndDefaultPathsPublishOneDocumentForThisServer() throws Exception {
        JsonNode legacy = document("/api-docs");
        JsonNode v3 = document("/v3/api-docs");
        assertThat(legacy.get("servers").get(0).get("url").asString()).isEqualTo(origin());
        assertThat(v3).isEqualTo(legacy);
        assertThat(legacy.get("paths").has("/v3/api-docs")).isFalse();
    }

    @Test
    void swaggerUiLoadsItsConfigAndTheLegacyDocument() throws Exception {
        assertThat(send("/swagger-ui.html").statusCode()).isEqualTo(200);
        var initializer = send("/swagger-ui/swagger-initializer.js");
        assertThat(initializer.statusCode()).isEqualTo(200);
        var configUrl = CONFIG_URL.matcher(initializer.body());
        assertThat(configUrl.find()).isTrue();
        JsonNode config = document(configUrl.group(1));
        assertThat(document(config.get("url").asString())).isEqualTo(document("/api-docs"));
    }

    private JsonNode document(String path) throws IOException, InterruptedException {
        var response = send(path);
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> send(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(origin() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String origin() { return "http://localhost:" + port; }
}
