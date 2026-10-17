package io.micronaut.jaxrs.container;

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The {@code @Context} fields of an instance that is not a bean, e.g. of a client provider: with
 * reflection, the private ones of the class and its super classes, an already set one is kept.
 */
@DisabledIfSystemProperty(named = "micronaut.jaxrs.test.reflection", matches = "false")
class ContextFieldInjectionTest {

    @Test
    void privateContextFieldsOfTheHierarchyAreInjected() {
        Providers providers = new JaxRsProviders(null);
        Provider provider = new Provider();
        JaxRsReflection.get().injectFields(provider, Context.class, type -> type == Providers.class ? providers : null);
        assertSame(providers, provider.inherited());
        assertSame(providers, provider.own);
        assertEquals("kept", provider.text);
    }

    static class Base {
        @Context
        private Providers inherited;

        Providers inherited() {
            return inherited;
        }
    }

    static final class Provider extends Base {
        @Context
        private Providers own;

        @Context
        private String text = "kept";
    }
}
