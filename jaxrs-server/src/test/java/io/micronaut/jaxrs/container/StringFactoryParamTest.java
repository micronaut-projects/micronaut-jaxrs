package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The parameters of types created from a String with their static valueOf or fromString method,
 * or an enum, also without reflection: valueOf before fromString, except for an enum.
 */
@MicronautTest
@Property(name = "spec.name", value = "StringFactoryParamTest")
class StringFactoryParamTest {

    @Inject
    @Client("/api/string-factory")
    HttpClient client;

    @Test
    void methodParameters() {
        assertEquals("valueOf:a fromString:b BLUE valueOf:c",
            client.toBlocking().retrieve(HttpRequest.GET("/method/c?v=a&f=b").header("color", "blue")));
    }

    @Test
    void collectionsOfParameters() {
        assertEquals("[valueOf:a, valueOf:b] [fromString:x, fromString:y]",
            client.toBlocking().retrieve(HttpRequest.GET("/collections;m=x;m=y?v=a&v=b")));
    }

    @Test
    void fieldsAndBeanParameters() {
        assertEquals("valueOf:a fromString:b RED",
            client.toBlocking().retrieve(HttpRequest.GET("/fields?v=a&f=b&c=red")));
    }

    @Test
    void invalidValue() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/method/x?v=fail")));
        assertEquals(404, e.getStatus().getCode(), "a query parameter that cannot be converted is not found");
    }

    /** Both factories: valueOf is used. */
    public static final class WithValueOf {
        private final String value;

        private WithValueOf(String value) {
            this.value = value;
        }

        public static WithValueOf valueOf(String value) {
            if ("fail".equals(value)) {
                throw new IllegalArgumentException(value);
            }
            return new WithValueOf("valueOf:" + value);
        }

        public static WithValueOf fromString(String value) {
            return new WithValueOf("fromString:" + value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** Only fromString. */
    public static final class WithFromString implements Comparable<WithFromString> {
        private final String value;

        private WithFromString(String value) {
            this.value = value;
        }

        public static WithFromString fromString(String value) {
            return new WithFromString("fromString:" + value);
        }

        @Override
        public int compareTo(WithFromString o) {
            return value.compareTo(o.value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** An enum: its fromString before its valueOf. */
    public enum Color {
        RED, BLUE;

        public static Color fromString(String value) {
            return valueOf(value.toUpperCase());
        }
    }

    public static class Bean {
        @QueryParam("v")
        WithValueOf v;
        @QueryParam("f")
        WithFromString f;
    }

    @Requires(property = "spec.name", value = "StringFactoryParamTest")
    @Path("/string-factory")
    public static class StringFactoryResource {

        @QueryParam("c")
        Color color;

        @BeanParam
        Bean bean;

        @GET
        @Path("/method/{p}")
        @Produces("text/plain")
        public String method(@QueryParam("v") WithValueOf v, @QueryParam("f") WithFromString f,
                             @HeaderParam("color") Color color, @PathParam("p") WithValueOf p) {
            return v + " " + f + " " + color + " " + p;
        }

        @GET
        @Path("/collections")
        @Produces("text/plain")
        public String collections(@QueryParam("v") List<WithValueOf> v, @MatrixParam("m") SortedSet<WithFromString> m) {
            return v + " " + m;
        }

        @GET
        @Path("/fields")
        @Produces("text/plain")
        public String fields() {
            return bean.v + " " + bean.f + " " + color;
        }
    }
}
