package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The root resource class of a sub-resource locator known only at runtime is committed to under
 * the context path, which the router mounts the templates under.
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatorRootCommitmentTest")
@Property(name = "micronaut.server.context-path", value = "/ctx")
class LocatorRootCommitmentContextPathTest {

    @Inject
    @Client("/ctx/api/commitment")
    HttpClient client;

    @Test
    void locatorOfALessSpecificRootClassIsNotInvoked() {
        assertEquals("other", client.toBlocking().retrieve(HttpRequest.GET("/other/next")));
        assertEquals("known", client.toBlocking().retrieve(HttpRequest.GET("/fixed/known")));
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/fixed/next")));
        assertEquals(404, e.getStatus().getCode());
    }
}
