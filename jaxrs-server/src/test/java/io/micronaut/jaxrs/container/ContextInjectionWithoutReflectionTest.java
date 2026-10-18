package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ResourceContext;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without the micronaut-reflection module, the {@code @Context} fields and setters of an instance
 * that is not a bean are injected from the introspection the processor generates, which keeps
 * them as properties.
 */
@MicronautTest
@Property(name = "spec.name", value = "ContextInjectionWithoutReflectionTest")
@EnabledIfSystemProperty(named = "micronaut.jaxrs.test.reflection", matches = "false")
class ContextInjectionWithoutReflectionTest {

    @Inject
    @Client("/api/context-injection")
    HttpClient client;

    @Test
    void providerContextFieldAndSetterAreInjected() {
        assertFalse(JaxRsReflection.get().isReflective());
        Providers providers = new JaxRsProviders(null);
        Providers kept = new JaxRsProviders(null);
        ContextProvider provider = new ContextProvider();
        provider.preset = kept;
        JaxRsReflection.get().injectFields(provider, Context.class, type -> type == Providers.class ? providers : null);
        assertSame(providers, provider.field());
        assertSame(providers, provider.setter);
        assertSame(kept, provider.preset);
    }

    @Test
    void introspectionKeepsTheContextMembers() {
        BeanIntrospection<ContextProvider> introspection = BeanIntrospection.getIntrospection(ContextProvider.class);
        assertTrue(introspection.getProperty("providers").orElseThrow().hasAnnotation(Context.class));
        assertTrue(introspection.getProperty("setterProviders").orElseThrow().hasAnnotation(Context.class));
    }

    @Test
    void applicationSingletonResourceIsInjected() {
        assertEquals("true", client.toBlocking().retrieve(HttpRequest.GET("/singleton")));
    }

    @Test
    void resourceContextResourceWithoutResourceMethodsIsInjected() {
        assertEquals("true true", client.toBlocking().retrieve(HttpRequest.GET("/resource-context")));
    }

    @Requires(property = "spec.name", value = "ContextInjectionWithoutReflectionTest")
    @Provider
    public static class ContextProvider implements ContextResolver<String> {
        @Context
        private Providers providers;

        @Context
        Providers preset;

        Providers setter;

        @Context
        public void setSetterProviders(Providers providers) {
            this.setter = providers;
        }

        Providers field() {
            return providers;
        }

        @Override
        public String getContext(Class<?> type) {
            return null;
        }
    }

    @Requires(property = "spec.name", value = "ContextInjectionWithoutReflectionTest")
    @Replaces(RestApplication.class)
    @Singleton
    @ApplicationPath("/api")
    public static class ContextApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(ResourceContextResource.class);
        }

        @Override
        public Set<Object> getSingletons() {
            return Set.of(new SingletonResource());
        }
    }

    @Requires(property = "spec.name", value = "ContextInjectionWithoutReflectionTest")
    @Path("/context-injection/singleton")
    public static class SingletonResource {
        @Context
        private Providers providers;

        @GET
        public String get() {
            return String.valueOf(providers != null);
        }
    }

    @Requires(property = "spec.name", value = "ContextInjectionWithoutReflectionTest")
    @Path("/context-injection/resource-context")
    public static class ResourceContextResource {
        @Context
        ResourceContext resourceContext;

        @GET
        public String get() {
            NoMethods initialized = resourceContext.initResource(new NoMethods());
            NoMethods obtained = resourceContext.getResource(NoMethods.class);
            return (initialized.providers != null) + " " + (obtained.providers != null);
        }
    }

    @Requires(property = "spec.name", value = "ContextInjectionWithoutReflectionTest")
    @Path("/context-injection/no-methods")
    public static class NoMethods {
        @Context
        private Providers providers;
    }
}
