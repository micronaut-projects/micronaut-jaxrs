package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableHeadersMultivaluedMap;
import io.micronaut.jaxrs.common.JaxRsMutableObjectHeadersMultivaluedMap;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry set, key set and values of the mutable {@link MultivaluedMap} views of headers write
 * through to the headers.
 */
@MicronautTest
@Property(name = "spec.name", value = "HeadersMultivaluedMapViewTest")
class HeadersMultivaluedMapViewTest {

    @Inject
    @Client("/api/headers-view")
    HttpClient client;

    @Test
    void stringViewsWriteThrough() {
        SimpleHttpHeaders headers = headers();
        MultivaluedMap<String, String> map = new JaxRsMutableHeadersMultivaluedMap(headers);
        assertEquals(3, map.entrySet().size());
        map.keySet().remove("A");
        assertFalse(headers.contains("A"));
        Iterator<Map.Entry<String, List<String>>> iterator = map.entrySet().iterator();
        Map.Entry<String, List<String>> entry = iterator.next();
        entry.setValue(List.of("x", "y"));
        assertEquals(List.of("x", "y"), headers.getAll(entry.getKey()));
        iterator.next();
        iterator.remove();
        assertEquals(1, headers.names().size());
        map.clear();
        assertTrue(headers.names().isEmpty());
        assertTrue(map.isEmpty());
    }

    @Test
    void objectViewsWriteThrough() {
        SimpleHttpHeaders headers = headers();
        MultivaluedMap<String, Object> map = new JaxRsMutableObjectHeadersMultivaluedMap(headers);
        map.values().removeIf(values -> values.contains("2"));
        assertFalse(headers.contains("B"));
        map.entrySet().clear();
        assertTrue(headers.names().isEmpty());
    }

    @Test
    void filterClearsTheRequestHeaders() {
        assertEquals("null", client.toBlocking().retrieve(HttpRequest.GET("/").header("Authorization", "secret")));
    }

    private static SimpleHttpHeaders headers() {
        SimpleHttpHeaders headers = new SimpleHttpHeaders();
        headers.add("A", "1");
        headers.add("B", "2");
        headers.add("C", "3");
        return headers;
    }

    @Requires(property = "spec.name", value = "HeadersMultivaluedMapViewTest")
    @Provider
    public static class ClearingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getUriInfo().getPath().startsWith("/headers-view")) {
                requestContext.getHeaders().clear();
            }
        }
    }

    @Requires(property = "spec.name", value = "HeadersMultivaluedMapViewTest")
    @Path("/headers-view")
    public static class HeadersResource {
        @GET
        @Produces("text/plain")
        public String get(@HeaderParam("Authorization") String authorization) {
            return String.valueOf(authorization);
        }
    }
}
