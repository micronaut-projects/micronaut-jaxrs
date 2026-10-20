package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The default value of a parameter is converted by its {@link ParamConverter} when the routes are
 * built, failing the deployment, unless the converter is {@link ParamConverter.Lazy}.
 */
class ParamConverterDefaultValueTest {

    @Test
    void invalidDefaultValueFailsTheDeployment() {
        assertDeploymentFails("ParamConverterDefaultValueTest.eager", "/api/default-value/eager");
    }

    @Test
    void invalidDefaultValueOfABeanParamMemberFailsTheDeployment() {
        assertDeploymentFails("ParamConverterDefaultValueTest.bean", "/api/default-value/bean");
    }

    private static void assertDeploymentFails(String spec, String path) {
        Exception failure = assertThrows(Exception.class, () -> {
            try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", spec));
                 HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
                // the routes may be built on the first request
                client.toBlocking().retrieve(HttpRequest.GET(path));
            }
        });
        Throwable cause = failure;
        while (cause != null && (cause.getMessage() == null || !cause.getMessage().contains("cannot be converted"))) {
            cause = cause.getCause();
        }
        assertNotNull(cause, () -> "unexpected failure " + failure);
    }

    @Test
    void lazyConverterConvertsTheDefaultValueWhenUsed() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "ParamConverterDefaultValueTest.lazy"));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("v:7", client.toBlocking().retrieve(HttpRequest.GET("/api/default-value/lazy?lazy=7")));
            HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/api/default-value/lazy")));
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
    }

    public record Value(String value) {
    }

    public static class Converter implements ParamConverter<Value> {
        @Override
        public Value fromString(String value) {
            if (value.startsWith("bad")) {
                throw new IllegalArgumentException("bad value");
            }
            return new Value("v:" + value);
        }

        @Override
        public String toString(Value value) {
            return value.value();
        }
    }

    // introspected: its @Lazy is known without reflection
    @io.micronaut.core.annotation.Introspected
    @ParamConverter.Lazy
    public static class LazyConverter extends Converter {
    }

    @Requires(property = "spec.name", pattern = "ParamConverterDefaultValueTest.*")
    @Provider
    public static class Converters implements ParamConverterProvider {
        @SuppressWarnings("unchecked")
        @Override
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType != Value.class) {
                return null;
            }
            for (Annotation annotation : annotations) {
                if (annotation instanceof QueryParam queryParam && queryParam.value().equals("lazy")) {
                    return (ParamConverter<T>) new LazyConverter();
                }
            }
            return (ParamConverter<T>) new Converter();
        }
    }

    @Requires(property = "spec.name", value = "ParamConverterDefaultValueTest.eager")
    @Path("/default-value/eager")
    public static class EagerResource {
        @GET
        @Produces("text/plain")
        public String get(@DefaultValue("bad") @QueryParam("v") Value value) {
            return value.value();
        }
    }

    @Requires(property = "spec.name", value = "ParamConverterDefaultValueTest.lazy")
    @Path("/default-value/lazy")
    public static class LazyResource {
        @GET
        @Produces("text/plain")
        public String get(@DefaultValue("bad") @QueryParam("lazy") Value value) {
            return value.value();
        }
    }

    /**
     * A bean parameter with a member whose default value is not valid.
     */
    public static class Bean {
        @DefaultValue("bad")
        @QueryParam("v")
        Value value;
    }

    @Requires(property = "spec.name", value = "ParamConverterDefaultValueTest.bean")
    @Path("/default-value/bean")
    public static class BeanResource {
        @GET
        @Produces("text/plain")
        public String get(@BeanParam Bean bean) {
            return bean.value.value();
        }
    }
}
