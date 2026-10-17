package io.micronaut.jaxrs.container;

import io.micronaut.core.type.Argument;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import jakarta.ws.rs.core.GenericType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The argument of a type the JAX-RS API gives: with reflection, a wildcard type argument is read
 * as its bound, not dropped.
 */
@DisabledIfSystemProperty(named = "micronaut.jaxrs.test.reflection", matches = "false")
class GenericTypeArgumentTest {

    @Test
    void wildcardTypeArgumentIsReadAsItsBound() {
        Argument<?> argument = JaxRsArgumentUtil.from(new GenericType<List<? extends Number>>() { });
        assertEquals(List.class, argument.getType());
        assertEquals(Number.class, argument.getTypeParameters()[0].getType());
    }
}
