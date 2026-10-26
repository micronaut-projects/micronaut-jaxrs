package example.extension;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.jaxrs.container.RestApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A provider class an {@link Application} lists that is neither a bean nor annotated with
 * {@code @Provider} is a provider of the application (JAX-RS 2.3, 4.1.1): with reflection, see
 * {@link ApplicationListedProviderWithoutReflectionTest}.
 */
@MicronautTest
@Property(name = "spec.name", value = "ApplicationListedProviderTest")
class ApplicationListedProviderTest {

    @Inject
    @Client("/api/listed-provider")
    HttpClient client;

    @Test
    void listedWriterWrites() {
        assertEquals("listed:value", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Requires(property = "spec.name", value = "ApplicationListedProviderTest")
    @Replaces(RestApplication.class)
    @Singleton
    @ApplicationPath("/api")
    public static class ListedApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(ListedResource.class, PlainWriter.class);
        }
    }

    /**
     * Not a bean, not annotated with {@code @Provider}.
     */
    @Produces("text/plain")
    public static class PlainWriter implements MessageBodyWriter<String> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == String.class;
        }

        @Override
        public void writeTo(String value, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("listed:" + value).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "ApplicationListedProviderTest")
    @Path("/listed-provider")
    public static class ListedResource {
        @GET
        @Produces("text/plain")
        public String get() {
            return "value";
        }
    }
}
