package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.AnnotationMetadata;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The resource method of a route is equal to itself only.
 */
class JaxRsRouteResourceTest {

    @Test
    void resourceMethodOfARouteIsEqualToItselfOnly() {
        JaxRsRouteResource first = resource();
        JaxRsRouteResource second = resource();
        assertEquals(first, first);
        assertNotEquals(first, second);
        assertNotEquals(second, first);
        assertNotEquals(first, null);
    }

    private static JaxRsRouteResource resource() {
        return new JaxRsRouteResource(Object.class, () -> null, () -> AnnotationMetadata.EMPTY_METADATA, () -> new Annotation[0]);
    }
}
