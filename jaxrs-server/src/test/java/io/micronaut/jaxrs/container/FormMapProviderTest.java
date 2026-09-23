package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The standard form providers of {@code MultivaluedMap} are JAX-RS providers: sorted with the ones
 * of the application by their media type first (JAX-RS 4.2.3, 4.2.4).
 */
@MicronautTest
@Property(name = "spec.name", value = "FormMapProviderTest")
class FormMapProviderTest {

    @Inject
    EmbeddedServer server;

    private HttpResponse<String> post(String path, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/form-map/" + path))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString("a=1&b=x+y&a=2"))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void everyValueOfARepeatedFieldIsRead() throws Exception {
        HttpResponse<String> response = post("read", MediaType.APPLICATION_FORM_URLENCODED);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("{a=[1, 2], b=[x y]}", response.body());
    }

    @Test
    void standardProviderOfTheFormTypeIsSortedBeforeAnApplicationProviderOfAnyType() throws Exception {
        // the application provider reads any type as */*: the standard one declares the form type
        HttpResponse<String> response = post("read", MediaType.APPLICATION_FORM_URLENCODED + ";charset=UTF-8");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("{a=[1, 2], b=[x y]}", response.body());
    }

    @Test
    void mapIsWrittenAsAForm() throws Exception {
        HttpResponse<String> response = post("echo", MediaType.APPLICATION_FORM_URLENCODED);
        assertEquals(200, response.statusCode(), response.body());
        // the space percent-encoded, as the writer encoded it before
        assertEquals("a=1&a=2&b=x%20y", response.body());
    }

    @Requires(property = "spec.name", value = "FormMapProviderTest")
    @Provider
    @Consumes(MediaType.WILDCARD)
    public static class AnyTypeReader implements MessageBodyReader<Object> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return MultivaluedMap.class.isAssignableFrom(type);
        }

        @Override
        public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
            MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
            map.add("read by", "AnyTypeReader");
            return map;
        }
    }

    @Requires(property = "spec.name", value = "FormMapProviderTest")
    @Path("/form-map")
    public static class FormMapResource {
        @POST
        @Path("read")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        @Produces(MediaType.TEXT_PLAIN)
        public String read(MultivaluedMap<String, String> form) {
            return new TreeMap<>(form).toString();
        }

        @POST
        @Path("echo")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        @Produces(MediaType.APPLICATION_FORM_URLENCODED)
        public MultivaluedMap<String, String> echo(MultivaluedMap<String, String> form) {
            MultivaluedMap<String, String> sorted = new MultivaluedHashMap<>();
            new TreeMap<>(form).forEach(sorted::put);
            return sorted;
        }
    }
}
