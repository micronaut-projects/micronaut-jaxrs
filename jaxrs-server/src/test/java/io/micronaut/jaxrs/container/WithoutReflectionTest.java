package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Without the micronaut-reflection module, what needs reflection fails with an error that names it.
 */
@MicronautTest
@Property(name = "spec.name", value = "WithoutReflectionTest")
@EnabledIfSystemProperty(named = "micronaut.jaxrs.test.reflection", matches = "false")
class WithoutReflectionTest {

    @Inject
    @Client("/api/without-reflection")
    HttpClient client;

    @Test
    void resourceMethodNeedsTheReflectionModule() {
        assertEquals("io.micronaut:micronaut-reflection",
            client.toBlocking().retrieve(HttpRequest.GET("/method"), String.class));
    }

    @Test
    void typeArgumentOfAProviderIsKnownWithoutReflection() {
        // e.g. of a ContextResolver an Application registers as an instance: from the type
        // hierarchy the processor recorded
        assertFalse(JaxRsReflection.get().isReflective());
        Argument<?> resolved = JaxRsReflection.get().resolveGeneric(NumberResolver.class, ContextResolver.class);
        assertNotNull(resolved);
        assertEquals(ContextResolver.class, resolved.getType());
        assertEquals(Number.class, resolved.getTypeParameters()[0].getType());
    }

    @Test
    void pathOfAResourceMethodIsKnownWithoutReflection() throws NoSuchMethodException {
        // a Method the application gives: its @Path is read from the introspection of the resource
        assertEquals("/without-reflection/class",
            UriBuilder.fromResource(Resource.class).path(Resource.class.getMethod("resourceClass")).build().getPath());
    }

    @Test
    void resourceClassIsKnownWithoutReflection() {
        assertEquals(Resource.class.getName(),
            client.toBlocking().retrieve(HttpRequest.GET("/class"), String.class));
    }

    @Requires(property = "spec.name", value = "WithoutReflectionTest")
    @Path("/without-reflection")
    public static class Resource {
        @Context
        ResourceInfo resourceInfo;

        @GET
        @Path("method")
        public String method() {
            try {
                return resourceInfo.getResourceMethod().getName();
            } catch (UnsupportedOperationException e) {
                return e.getMessage().contains("io.micronaut:micronaut-reflection") ? "io.micronaut:micronaut-reflection" : e.getMessage();
            }
        }

        @GET
        @Path("class")
        public String resourceClass() {
            return resourceInfo.getResourceClass().getName();
        }
    }

    // not a bean of the test: a provider the processor saw
    @Requires(property = "spec.name", value = "WithoutReflectionTest.NumberResolver")
    @Provider
    public static class NumberResolver implements ContextResolver<Number> {
        @Override
        public Number getContext(Class<?> type) {
            return 1;
        }
    }
}
