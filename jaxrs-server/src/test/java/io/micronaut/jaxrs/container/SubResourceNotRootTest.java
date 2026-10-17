package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A class without a {@code @Path} is a sub-resource, not a root resource.
 */
@MicronautTest
@Property(name = "spec.name", value = "SubResourceNotRootTest")
class SubResourceNotRootTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void subResourceIsNotRoutedFromTheRoot() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/not-root-secret")));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test
    void subResourceIsRoutedThroughItsLocator() {
        assertEquals("secret", client.toBlocking().retrieve(HttpRequest.GET("/not-root/vault/not-root-secret")));
    }

    @Requires(property = "spec.name", value = "SubResourceNotRootTest")
    @Path("not-root")
    public static class Root {
        @Inject
        Vault vault;

        @Path("vault")
        public Vault vault() {
            return vault;
        }
    }

    @Requires(property = "spec.name", value = "SubResourceNotRootTest")
    public static class Vault {
        @GET
        @Path("not-root-secret")
        public String secret() {
            return "secret";
        }
    }
}
