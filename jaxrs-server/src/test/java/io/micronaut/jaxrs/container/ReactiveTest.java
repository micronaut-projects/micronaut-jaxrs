package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
@Property(name = "spec.name", value = "ReactiveTest")
class ReactiveTest {

    @Inject
    @Client("/api/reactive")
    HttpClient client;

    @ParameterizedTest
    @ValueSource(strings = {"/flux", "/mono"})
    void testReactive(String endpoint) {
        assertEquals("foo", client.toBlocking().retrieve(endpoint));
    }

    @Requires(property = "spec.name", value = "ReactiveTest")
    @Path("/reactive")
    static class MyController {
        @GET
        @Path("/flux")
        public Publisher<String> flux() {
            return Flux.just("foo");
        }

        @GET
        @Path("/mono")
        public Publisher<String> mono() {
            return Mono.just("foo");
        }
    }
}
