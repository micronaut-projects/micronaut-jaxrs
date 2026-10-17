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
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code ResourceInfo} of a resource method of a sub-resource that is not a bean: its class and
 * method, not the ones of the handler of the route.
 */
@MicronautTest
@Property(name = "spec.name", value = "SubResourceInfoTest")
@DisabledIfSystemProperty(named = "micronaut.jaxrs.test.reflection", matches = "false")
class SubResourceInfoTest {

    @Inject
    @Client("/api/sub-resource-info")
    HttpClient client;

    @Test
    void resourceMethodOfASubResource() {
        assertEquals(Located.class.getName() + "#info",
            client.toBlocking().retrieve(HttpRequest.GET("/located"), String.class));
    }

    @Requires(property = "spec.name", value = "SubResourceInfoTest")
    @Path("/sub-resource-info")
    public static class RootResource {
        @Path("located")
        public Located located() {
            return new Located();
        }
    }

    public static class Located {
        @GET
        public String info(@Context ResourceInfo resourceInfo) {
            return resourceInfo.getResourceClass().getName() + "#" + resourceInfo.getResourceMethod().getName();
        }
    }
}
