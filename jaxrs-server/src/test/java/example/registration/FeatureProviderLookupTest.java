package example.registration;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.jaxrs.container.RestApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A feature that looks up a writer before it registers it: the lookup at startup does not hide
 * the writer it registers afterwards (FeatureContext#register, Providers#getMessageBodyWriter).
 */
@MicronautTest
@Property(name = "spec.name", value = "FeatureProviderLookupTest")
class FeatureProviderLookupTest {

    @Inject
    @Client("/api/feature-lookup")
    HttpClient client;

    @Test
    void theWriterTheFeatureRegistersIsFound() {
        assertEquals("before:none after:found", client.toBlocking().retrieve(HttpRequest.GET("/lookup")));
    }

    @Test
    void theWriterTheFeatureRegistersWrites() {
        assertEquals("written x", client.toBlocking().retrieve(HttpRequest.GET("/message")));
    }

    @Requires(property = "spec.name", value = "FeatureProviderLookupTest")
    @Replaces(RestApplication.class)
    @ApplicationPath("/api")
    public static class LookupApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(LookupResource.class, LookupFeature.class);
        }
    }

    public record Message(String value) {
    }

    private static MessageBodyWriter<Message> lookup(Providers providers) {
        return providers.getMessageBodyWriter(Message.class, Message.class, new Annotation[0], MediaType.TEXT_PLAIN_TYPE);
    }

    @Requires(property = "spec.name", value = "FeatureProviderLookupTest")
    @Path("/feature-lookup")
    public static class LookupResource {
        @GET
        @Path("lookup")
        @Produces("text/plain")
        public String lookup(@Context Providers providers) {
            return "before:" + LookupFeature.before + " after:" + (FeatureProviderLookupTest.lookup(providers) == null ? "none" : "found");
        }

        @GET
        @Path("message")
        @Produces("text/plain")
        public Message message() {
            return new Message("x");
        }
    }

    @Requires(property = "spec.name", value = "FeatureProviderLookupTest")
    @Provider
    public static class LookupFeature implements Feature {
        static volatile String before;

        @Inject
        Providers providers;

        @Override
        public boolean configure(FeatureContext context) {
            // the writer is not registered yet
            before = FeatureProviderLookupTest.lookup(providers) == null ? "none" : "found";
            context.register(MessageWriter.class);
            return true;
        }
    }

    /**
     * Not listed, registered by the feature.
     */
    @Requires(property = "spec.name", value = "FeatureProviderLookupTest")
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
