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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@link ParamConverterProvider} sees the annotations of each parameter: parameters of the same
 * type with other annotations get their own converter.
 */
@MicronautTest
@Property(name = "spec.name", value = "ParamConverterAnnotationsTest")
class ParamConverterAnnotationsTest {

    @Inject
    @Client("/api/param-converter-annotations")
    HttpClient client;

    @Test
    void converterOfEachParameter() {
        // parameters of the same name and type, which are equal arguments
        assertEquals("ABC", client.toBlocking().retrieve(HttpRequest.GET("/upper?v=abc")));
        assertEquals("abc", client.toBlocking().retrieve(HttpRequest.GET("/plain?v=abc")));
    }

    @Test
    void elementsOfCollectionsAndArraysSeeTheAnnotationsOfTheParameter() {
        assertEquals("[A, B]", client.toBlocking().retrieve(HttpRequest.GET("/upper-list?v=a&v=b")));
        assertEquals("[A, B]", client.toBlocking().retrieve(HttpRequest.GET("/upper-array?v=a&v=b")));
        assertEquals("[a, b]", client.toBlocking().retrieve(HttpRequest.GET("/plain-list?v=a&v=b")));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Upper {
    }

    public record Text(String value) {
    }

    @Requires(property = "spec.name", value = "ParamConverterAnnotationsTest")
    @Path("/param-converter-annotations")
    public static class ConvertedResource {
        @GET
        @Path("/upper")
        @Produces("text/plain")
        public String upper(@QueryParam("v") @Upper Text v) {
            return v.value();
        }

        @GET
        @Path("/upper-list")
        @Produces("text/plain")
        public String upperList(@QueryParam("v") @Upper List<Text> v) {
            return v.stream().map(Text::value).toList().toString();
        }

        @GET
        @Path("/upper-array")
        @Produces("text/plain")
        public String upperArray(@QueryParam("v") @Upper Text[] v) {
            return Arrays.stream(v).map(Text::value).toList().toString();
        }

        @GET
        @Path("/plain-list")
        @Produces("text/plain")
        public String plainList(@QueryParam("v") List<Text> v) {
            return v.stream().map(Text::value).toList().toString();
        }

        @GET
        @Path("/plain")
        @Produces("text/plain")
        public String plain(@QueryParam("v") Text v) {
            return v.value();
        }
    }

    @Requires(property = "spec.name", value = "ParamConverterAnnotationsTest")
    @Provider
    public static class TextConverterProvider implements ParamConverterProvider {
        @SuppressWarnings("unchecked")
        @Override
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType != Text.class) {
                return null;
            }
            boolean upper = false;
            for (Annotation annotation : annotations) {
                upper |= annotation instanceof Upper;
            }
            boolean toUpper = upper;
            return (ParamConverter<T>) new ParamConverter<Text>() {
                @Override
                public Text fromString(String value) {
                    return new Text(toUpper ? value.toUpperCase(Locale.ROOT) : value);
                }

                @Override
                public String toString(Text value) {
                    return value.value();
                }
            };
        }
    }
}
