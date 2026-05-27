package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class RequestContextTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void requestMethodUsesCurrentHttpMethod() {
        String body = client.toBlocking()
            .retrieve(HttpRequest.GET("/api/request-context/method"), String.class);

        assertEquals("GET", body);
    }

    @Test
    void selectVariantRejectsNullVariants() {
        String body = client.toBlocking()
            .retrieve(HttpRequest.GET("/api/request-context/variant-null"), String.class);

        assertEquals("ok", body);
    }

    @Test
    void selectVariantSetsVaryHeaders() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/api/request-context/variant")
            .header(HttpHeaders.ACCEPT, "application/json")
            .header(HttpHeaders.ACCEPT_ENCODING, "*")
            .header(HttpHeaders.ACCEPT_LANGUAGE, "*"), String.class);

        assertEquals(HttpStatus.OK, response.getStatus());
        List<String> varyHeaders = response.getHeaders().getAll(HttpHeaders.VARY)
            .stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .toList();
        assertTrue(varyHeaders.stream().anyMatch(HttpHeaders.ACCEPT::equalsIgnoreCase));
        assertTrue(varyHeaders.stream().anyMatch(HttpHeaders.ACCEPT_LANGUAGE::equalsIgnoreCase));
        assertTrue(varyHeaders.stream().anyMatch(HttpHeaders.ACCEPT_ENCODING::equalsIgnoreCase));
    }

    @Test
    void evaluatePreconditionsMatchesEntityTags() {
        HttpStatus ok = status(HttpRequest.GET("/api/request-context/preconditions/entity-tag")
            .header(HttpHeaders.IF_MATCH, "\"AAA\""));
        HttpStatus failed = status(HttpRequest.GET("/api/request-context/preconditions/entity-tag")
            .header(HttpHeaders.IF_MATCH, "\"BBB\""));

        assertEquals(HttpStatus.OK, ok);
        assertEquals(HttpStatus.PRECONDITION_FAILED, failed);
    }

    @Test
    void evaluatePreconditionsRejectsNullEntityTag() {
        HttpResponse<String> response = client.toBlocking()
            .exchange(HttpRequest.GET("/api/request-context/preconditions/entity-tag-null"), String.class);

        assertEquals(HttpStatus.OK, response.getStatus());
    }

    @Test
    void evaluatePreconditionsComparesDates() {
        HttpStatus oldModified = status(HttpRequest.GET("/api/request-context/preconditions/date-old")
            .header(HttpHeaders.IF_MODIFIED_SINCE, "Sat, 29 Oct 1994 19:43:31 GMT"));
        HttpStatus oldUnmodified = status(HttpRequest.GET("/api/request-context/preconditions/date-old")
            .header(HttpHeaders.IF_UNMODIFIED_SINCE, "Sat, 29 Oct 1994 19:43:31 GMT"));
        HttpStatus nowModified = status(HttpRequest.GET("/api/request-context/preconditions/date-now")
            .header(HttpHeaders.IF_MODIFIED_SINCE, "Sat, 29 Oct 1994 19:43:31 GMT"));
        HttpStatus nowUnmodified = status(HttpRequest.GET("/api/request-context/preconditions/date-now")
            .header(HttpHeaders.IF_UNMODIFIED_SINCE, "Sat, 29 Oct 1994 19:43:31 GMT"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, oldModified);
        assertEquals(HttpStatus.OK, oldUnmodified);
        assertEquals(HttpStatus.OK, nowModified);
        assertEquals(HttpStatus.PRECONDITION_FAILED, nowUnmodified);
    }

    private HttpStatus status(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }
}
