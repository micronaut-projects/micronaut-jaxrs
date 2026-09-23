package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The root resource class with the most specific {@code @Path} that matches is selected first,
 * then one of its methods (JAX-RS 3.7.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "RootResourceMatchingTest")
class RootResourceMatchingTest {

    @Inject
    @Client("/api/matching")
    HttpClient client;

    private String get(String path) {
        return client.toBlocking().retrieve(HttpRequest.GET(path), String.class);
    }

    @Test
    void mostSpecificRootClassIsSelected() {
        assertEquals("sub", get("/sub/located"));
    }

    @Test
    void lessSpecificRootClassMatchesOtherPaths() {
        assertEquals("main", get("/other"));
    }

    @Test
    void noMethodOfTheSelectedRootClassMatches() {
        // MainResource has a method for the path, but SubResource is the most specific root class
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> get("/sub/located/more"));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test
    void optionsIsAnsweredWithTheAllowedMethods() {
        // without an OPTIONS resource method (JAX-RS 3.3.5)
        HttpResponse<?> response = client.toBlocking().exchange(HttpRequest.OPTIONS("/something"));
        assertEquals(200, response.getStatus().getCode());
        Set<String> allowed = response.getHeaders().getAll(HttpHeaders.ALLOW).stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .collect(Collectors.toSet());
        assertEquals(Set.of("GET", "HEAD", "OPTIONS"), allowed);
    }

    @Requires(property = "spec.name", value = "RootResourceMatchingTest")
    @Path("/matching")
    public static class MainResource {
        @GET
        @Path("sub/located/more")
        public String more() {
            return "main more";
        }

        @GET
        @Path("{id}")
        public String id() {
            return "main";
        }
    }

    @Requires(property = "spec.name", value = "RootResourceMatchingTest")
    @Path("/matching/sub")
    public static class SubResource {
        @Path("located")
        public Located located() {
            return new Located();
        }
    }

    public static class Located {
        @GET
        public String get() {
            return "sub";
        }
    }
}
