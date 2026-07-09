package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@MicronautTest
@Property(name = "micronaut.router.static-resources.swagger.paths", value = "classpath:META-INF/swagger")
@Property(name = "micronaut.router.static-resources.swagger.mapping", value = "/swagger/**")
@Property(name = "micronaut.router.static-resources.swagger-ui.paths", value = "classpath:META-INF/swagger/views/swagger-ui")
@Property(name = "micronaut.router.static-resources.swagger-ui.mapping", value = "/swagger-ui/**")
@Property(name = "micronaut.openapi.serialization.framework", value = "jackson")
class SwaggerUiStaticResourceReproducerTest {

    @Inject
    @Client("/")
    HttpClient rootClient;

    @Test
    void servesStaticHtmlInsteadOfSerializedSystemFile() {
        HttpResponse<String> response = rootClient.toBlocking().exchange(
            HttpRequest.GET("/swagger-ui/index.html").accept("text/html"),
            String.class
        );

        String body = response.body();
        Assertions.assertEquals(200, response.code());
        Assertions.assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE).orElse("").startsWith("text/html"));
        Assertions.assertTrue(body.contains("JAXRS Swagger UI Reproducer"));
        Assertions.assertFalse(body.contains("\"rawType\""));
        Assertions.assertFalse(body.contains("\"entity\""));
        Assertions.assertFalse(body.contains("SystemFile"));
    }
}
