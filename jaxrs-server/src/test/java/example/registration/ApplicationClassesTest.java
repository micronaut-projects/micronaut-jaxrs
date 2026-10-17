package example.registration;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.jaxrs.container.RestApplication;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@link Application} that lists its classes registers only those and the ones its features
 * register: a filter it does not list does not run, a writer its feature registers writes.
 * Outside of the packages of Micronaut, whose providers are always registered.
 */
@MicronautTest
@Property(name = "spec.name", value = "ApplicationClassesTest")
class ApplicationClassesTest {

    @Inject
    @Client("/api/application-classes")
    HttpClient client;

    @Test
    void onlyTheRegisteredProviders() {
        assertEquals("written listed", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Requires(property = "spec.name", value = "ApplicationClassesTest")
    @Replaces(RestApplication.class)
    @ApplicationPath("/api")
    public static class ListingApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(ListedResource.class, ListedFilter.class, WriterFeature.class);
        }
    }

    public record Message(String value) {
    }

    @Requires(property = "spec.name", value = "ApplicationClassesTest")
    @Path("/application-classes")
    public static class ListedResource {
        @GET
        @Produces("text/plain")
        public Message get(@jakarta.ws.rs.HeaderParam("X-Filtered") String filtered) {
            return new Message(filtered);
        }
    }

    @Requires(property = "spec.name", value = "ApplicationClassesTest")
    @Provider
    public static class ListedFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getHeaderString("X-Filtered") == null) {
                requestContext.getHeaders().putSingle("X-Filtered", "listed");
            }
        }
    }

    /**
     * Not listed: it does not run.
     */
    @Requires(property = "spec.name", value = "ApplicationClassesTest")
    @Provider
    public static class UnlistedFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.getHeaders().putSingle("X-Filtered", "unlisted");
        }
    }

    @Requires(property = "spec.name", value = "ApplicationClassesTest")
    @Provider
    public static class WriterFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(MessageWriter.class);
            return true;
        }
    }

    /**
     * Not listed, registered by the feature.
     */
    @Requires(property = "spec.name", value = "ApplicationClassesTest")
    @Provider
    @Produces("text/plain")
    public static class MessageWriter implements MessageBodyWriter<Message> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Message.class;
        }

        @Override
        public void writeTo(Message message, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("written " + message.value()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
