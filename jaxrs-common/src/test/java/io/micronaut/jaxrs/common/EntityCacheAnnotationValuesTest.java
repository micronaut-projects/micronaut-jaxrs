package io.micronaut.jaxrs.common;

import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An entity read without being buffered is read again only with the same type and the same
 * annotations, values included: other values may select another reader, and the entity was
 * consumed.
 */
class EntityCacheAnnotationValuesTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Format {
        String value();
    }

    @Format("a")
    private static final class FormatA {
    }

    @Format("b")
    private static final class FormatB {
    }

    @Test
    void theSameAnnotationsReadTheSameEntity() {
        JaxRsMutableResponse response = new JaxRsMutableResponse(HttpResponse.ok("abc"));
        assertEquals("abc", response.readEntity(String.class, FormatA.class.getAnnotations()));
        assertEquals("abc", response.readEntity(String.class, FormatA.class.getAnnotations()));
    }

    @Test
    void otherAnnotationValuesDoNotReadTheConsumedEntity() {
        JaxRsMutableResponse response = new JaxRsMutableResponse(HttpResponse.ok("abc"));
        assertEquals("abc", response.readEntity(String.class, FormatA.class.getAnnotations()));
        assertThrows(IllegalStateException.class, () -> response.readEntity(String.class, FormatB.class.getAnnotations()));
    }
}
