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
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A resource method returning a subclass of {@code CompletionStage} of the application is
 * asynchronous: the response is its value (JAX-RS 8.2.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "CompletionStageSubclassTest")
class CompletionStageSubclassTest {

    @Inject
    @Client("/api/stage-subclass")
    HttpClient client;

    @Test
    void valueOfASubclassIsTheResponse() {
        assertEquals("done", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Test
    void valueOfASubclassIsWrittenWithTheTypeArgumentItGivesTheStage() {
        // the type argument a class gives its supertype is resolved with reflection
        Assumptions.assumeTrue(JaxRsReflection.get().isReflective());
        assertEquals("strings:a", client.toBlocking().retrieve(HttpRequest.GET("/strings").accept("text/x-strings"), String.class));
    }

    public static class MyFuture extends CompletableFuture<String> {
    }

    public static class StringsFuture extends CompletableFuture<List<String>> {
    }

    @Requires(property = "spec.name", value = "CompletionStageSubclassTest")
    @Provider
    @Produces("text/x-strings")
    public static class StringsWriter implements MessageBodyWriter<List<String>> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return List.class.isAssignableFrom(type) && genericType instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] == String.class;
        }

        @Override
        public void writeTo(List<String> strings, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("strings:" + String.join(",", strings)).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "CompletionStageSubclassTest")
    @Path("/stage-subclass")
    public static class Resource {

        @GET
        public MyFuture get() {
            MyFuture future = new MyFuture();
            future.complete("done");
            return future;
        }

        @GET
        @Path("strings")
        @Produces("text/x-strings")
        public StringsFuture strings() {
            StringsFuture future = new StringsFuture();
            future.complete(new ArrayList<>(List.of("a")));
            return future;
        }
    }
}
