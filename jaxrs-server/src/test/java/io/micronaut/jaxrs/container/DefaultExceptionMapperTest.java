package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default mapper of every exception, which {@link Providers} has without the mappers of the
 * application (JAX-RS 4.4).
 */
@MicronautTest
@Property(name = "spec.name", value = "DefaultExceptionMapperTest")
class DefaultExceptionMapperTest {

    @Inject
    @Client("/api/default-exception-mapper")
    HttpClient client;

    @Test
    void providersHaveTheDefaultMapper() {
        assertEquals("500", client.toBlocking().retrieve(HttpRequest.GET("/"), String.class));
    }

    @Requires(property = "spec.name", value = "DefaultExceptionMapperTest")
    @Path("/default-exception-mapper")
    @Produces(MediaType.TEXT_PLAIN)
    public static class MapperResource {
        @GET
        public String get(@Context Providers providers) {
            ExceptionMapper<Throwable> mapper = providers.getExceptionMapper(Throwable.class);
            return mapper == null ? "none" : String.valueOf(mapper.toResponse(new IllegalStateException()).getStatus());
        }
    }
}
