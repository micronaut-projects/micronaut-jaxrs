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
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@link GenericEntity} a response filter sets keeps the class of its entity and its generic
 * type for the writers.
 */
@MicronautTest
@Property(name = "spec.name", value = "ResponseFilterGenericEntityTest")
class ResponseFilterGenericEntityTest {

    @Inject
    @Client("/api/response-filter-generic-entity")
    HttpClient client;

    @Test
    void theWriterSeesTheClassAndTheGenericType() {
        assertEquals("array-list:[a, b]", client.toBlocking().retrieve(HttpRequest.GET("/"), String.class));
    }

    @Requires(property = "spec.name", value = "ResponseFilterGenericEntityTest")
    @Provider
    public static class GenericEntityFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            ArrayList<String> list = new ArrayList<>(List.of("a", "b"));
            responseContext.setEntity(new GenericEntity<List<String>>(list) { });
        }
    }

    @Requires(property = "spec.name", value = "ResponseFilterGenericEntityTest")
    @Provider
    @Produces(MediaType.TEXT_PLAIN)
    public static class ArrayListWriter implements MessageBodyWriter<ArrayList<String>> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == ArrayList.class && genericType instanceof ParameterizedType parameterized
                && parameterized.getRawType() == List.class && parameterized.getActualTypeArguments()[0] == String.class;
        }

        @Override
        public void writeTo(ArrayList<String> list, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("array-list:" + list).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "ResponseFilterGenericEntityTest")
    @Path("/response-filter-generic-entity")
    public static class EntityResource {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "replaced";
        }
    }
}
