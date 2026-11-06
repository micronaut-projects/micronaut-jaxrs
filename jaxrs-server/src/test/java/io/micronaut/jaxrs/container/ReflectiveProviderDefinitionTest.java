package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import jakarta.annotation.PostConstruct;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The definition of a provider class the annotation processors never saw (JAX-RS 4.1): a
 * singleton unless the class declares a scope, injected with its {@code @Context} members before
 * its {@code @PostConstruct} methods run.
 */
class ReflectiveProviderDefinitionTest {

    @Test
    void unscopedProviderIsASingleton() {
        assumeTrue(JaxRsReflection.get().isReflective());
        RuntimeBeanDefinition<UnscopedMapper> definition = JaxRsReflection.get().beanDefinition(UnscopedMapper.class);
        assertNotNull(definition);
        assertTrue(definition.isSingleton());
    }

    @Test
    void declaredScopeIsKept() {
        assumeTrue(JaxRsReflection.get().isReflective());
        RuntimeBeanDefinition<PrototypeMapper> definition = JaxRsReflection.get().beanDefinition(PrototypeMapper.class);
        assertNotNull(definition);
        assertFalse(definition.isSingleton());
    }

    @Test
    void contextMembersAreInjectedBeforePostConstruct() {
        assumeTrue(JaxRsReflection.get().isReflective());
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "ReflectiveProviderDefinitionTest"))) {
            RuntimeBeanDefinition<UnscopedMapper> definition = JaxRsReflection.get().beanDefinition(UnscopedMapper.class);
            JaxRsApplicationSingletons.registerReflective(context, definition);
            // the class is a generated bean too, here: the registered definition is asked for
            UnscopedMapper mapper = context.getBean(definition);
            assertNotNull(mapper.configuration);
            assertSame(mapper.configuration, mapper.initialized);
            assertSame(mapper, context.getBean(definition));
        }
    }

    @io.micronaut.context.annotation.Requires(property = "spec.name", value = "ReflectiveProviderDefinitionTest")
    public static class UnscopedMapper implements ExceptionMapper<IllegalStateException> {
        @Context
        Configuration configuration;
        Configuration initialized;

        @PostConstruct
        void init() {
            initialized = configuration;
        }

        @Override
        public Response toResponse(IllegalStateException exception) {
            return Response.ok().build();
        }
    }

    @io.micronaut.context.annotation.Requires(property = "spec.name", value = "ReflectiveProviderDefinitionTest")
    @io.micronaut.context.annotation.Prototype
    public static class PrototypeMapper implements ExceptionMapper<IllegalArgumentException> {
        @Override
        public Response toResponse(IllegalArgumentException exception) {
            return Response.ok().build();
        }
    }
}
