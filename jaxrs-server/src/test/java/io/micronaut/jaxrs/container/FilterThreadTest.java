package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The container filters may block, e.g. on a database: they do not run on an event loop.
 */
@MicronautTest
@Property(name = "spec.name", value = "FilterThreadTest")
class FilterThreadTest {

    @Inject
    @Client("/api/filter-thread")
    HttpClient client;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void filtersDoNotRunOnTheEventLoop(boolean withEntity) {
        HttpRequest<?> request = withEntity
            ? HttpRequest.POST("/", "hello").contentType(MediaType.TEXT_PLAIN_TYPE)
            : HttpRequest.GET("/");
        HttpResponse<String> response = client.toBlocking().exchange(request, String.class);
        assertEquals("false false", response.body(), "pre-matching, request");
        assertEquals("false", response.header("X-Response-Event-Loop"), "response");
    }

    static boolean onEventLoop() {
        return Thread.currentThread().getName().toLowerCase(java.util.Locale.ROOT).contains("eventloop");
    }

    private static boolean relevant(ContainerRequestContext context) {
        return context.getUriInfo().getPath().endsWith("filter-thread");
    }

    @Requires(property = "spec.name", value = "FilterThreadTest")
    @Provider
    @PreMatching
    public static class PreMatchingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext context) {
            if (relevant(context)) {
                context.getHeaders().putSingle("X-Pre-Event-Loop", String.valueOf(onEventLoop()));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterThreadTest")
    @Provider
    public static class RequestFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext context) {
            if (relevant(context)) {
                context.getHeaders().putSingle("X-Request-Event-Loop", String.valueOf(onEventLoop()));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterThreadTest")
    @Provider
    public static class ResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            if (relevant(request)) {
                response.getHeaders().putSingle("X-Response-Event-Loop", String.valueOf(onEventLoop()));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterThreadTest")
    @Path("/filter-thread")
    public static class Resource {

        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get(@jakarta.ws.rs.HeaderParam("X-Pre-Event-Loop") String pre,
                          @jakarta.ws.rs.HeaderParam("X-Request-Event-Loop") String request) {
            return pre + " " + request;
        }

        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        public String post(@jakarta.ws.rs.HeaderParam("X-Pre-Event-Loop") String pre,
                           @jakarta.ws.rs.HeaderParam("X-Request-Event-Loop") String request, String body) {
            return pre + " " + request;
        }
    }
}
