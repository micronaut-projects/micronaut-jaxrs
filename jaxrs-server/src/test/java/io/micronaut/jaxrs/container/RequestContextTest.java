package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class RequestContextTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    ApplicationProvider applicationProvider;

    @Test
    void requestContextPropertiesUseRequestAttributes() {
        MutableHttpRequest<?> request = HttpRequest.GET("/");
        request.setAttribute("existing", "value");
        JaxRsContainerRequestContext context = new JaxRsContainerRequestContext(request, applicationProvider);

        assertEquals("value", context.getProperty("existing"));
        assertTrue(context.hasProperty("existing"));

        context.setProperty("from-context", "seen");
        assertEquals("seen", request.getAttributes().getValue("from-context"));

        request.setAttribute("from-request", "visible");
        assertEquals("visible", context.getProperty("from-request"));

        context.setProperty("from-context", null);
        assertFalse(context.hasProperty("from-context"));
        assertThrows(UnsupportedOperationException.class, () -> context.getPropertyNames().add("blocked"));
    }

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

    @Test
    void preMatchingRequestFilterCanRerouteWithSetRequestUri() {
        String body = client.toBlocking()
            .retrieve(HttpRequest.GET("/api/request-context/reroute/source")
                .header(TestRequestContextResource.REROUTE_HEADER, "true"), String.class);

        assertEquals("target", body);
    }

    @Test
    void preMatchingRequestFilterCanRerouteWithSetMethod() {
        String body = client.toBlocking()
            .retrieve(HttpRequest.GET("/api/request-context/method-switch")
                .header(TestRequestContextResource.METHOD_OVERRIDE_HEADER, "OPTIONS"), String.class);

        assertEquals("OPTIONS", body);
    }

    private HttpStatus status(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }
}
