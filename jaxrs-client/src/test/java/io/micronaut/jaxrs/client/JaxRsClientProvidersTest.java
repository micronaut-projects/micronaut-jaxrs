package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import jakarta.ws.rs.sse.InboundSseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The selection of the entity providers of the client and the context they are injected with.
 */
@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsClientProvidersTest {

    @Inject
    EmbeddedServer server;

    Client client;

    @BeforeEach
    void setUp() {
        client = ClientBuilder.newClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private WebTarget echo() {
        return client.target(server.getURI()).path("client").path("echo");
    }

    @Test
    void aProviderRegisteredAsAReaderOnlyDoesNotWrite() {
        String echoed = echo()
            .register(new ThingReaderWriter("dual"), MessageBodyReader.class)
            .register(new ThingWriter("other"), 5000)
            .request()
            .post(Entity.text(new Thing("x")), String.class);

        assertEquals("text/plain|other:x", echoed);
    }

    @Test
    void aWriterThatDoesNotProduceTheMediaTypeIsNotSelected() {
        String echoed = echo()
            .register(JsonThingWriter.class)
            .register(TextObjectWriter.class)
            .request()
            .post(Entity.text(new Thing("x")), String.class);

        assertEquals("text/plain|object:x", echoed);
    }

    @Test
    void writersAreOrderedByTheirPriorities() {
        String echoed = echo()
            // of different classes: a class is registered once
            .register(new ThingWriter("low"), 5000)
            .register(new HighWriter(), 100)
            .request()
            .post(Entity.text(new Thing("x")), String.class);

        assertEquals("text/plain|high:x", echoed);
    }

    @Test
    void anInterceptorConstrainedToTheServerDoesNotRunOnTheClient() {
        String echoed = echo()
            .register(ServerInterceptor.class)
            .request()
            .post(Entity.text("body"), String.class);

        assertEquals("text/plain|body", echoed);
    }

    @Test
    void theContextResolverProducingTheMediaTypeIsSelected() {
        String echoed = echo()
            .register(new ContextWriter())
            .register(JsonResolver.class)
            .register(XmlResolver.class)
            .request()
            .post(Entity.entity(new Thing("x"), MediaType.APPLICATION_XML), String.class);

        assertEquals("application/xml|xml", echoed);
    }

    @Test
    void aSharedProviderUsesTheProvidersOfTheCurrentConfiguration() {
        client.register(new ContextWriter());
        assertEquals("application/xml|none", echo().request()
            .post(Entity.entity(new Thing("x"), MediaType.APPLICATION_XML), String.class));

        // a target derived after the provider was injected, with another provider
        String echoed = echo()
            .register(XmlResolver.class)
            .request()
            .post(Entity.entity(new Thing("x"), MediaType.APPLICATION_XML), String.class);

        assertEquals("application/xml|xml", echoed);
    }

    @Test
    void theDataOfAnEventIsReadWithTheProvidersOfTheTarget() {
        client.register(new ContextReader());
        // a target derived after the reader was injected, with another provider
        JaxRsWebTarget target = (JaxRsWebTarget) echo().register(XmlResolver.class);
        var event = new JaxRsInboundSseEvent(null, null, null, InboundSseEvent.RECONNECT_NOT_SET, "x", target.getConfiguration());

        assertEquals(new Thing("xml"), event.readData(Thing.class, MediaType.APPLICATION_XML_TYPE));
    }

    public record Thing(String value) {
    }

    private static void write(String text, OutputStream entityStream) throws IOException {
        entityStream.write(text.getBytes(StandardCharsets.UTF_8));
    }

    public static class ThingWriter implements MessageBodyWriter<Thing> {
        private final String name;

        public ThingWriter(String name) {
            this.name = name;
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public void writeTo(Thing thing, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            write(name + ":" + thing.value(), entityStream);
        }
    }

    public static final class ThingReaderWriter extends ThingWriter implements MessageBodyReader<Thing> {

        public ThingReaderWriter(String name) {
            super(name);
        }

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public Thing readFrom(Class<Thing> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return new Thing(new String(entityStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public static final class HighWriter extends ThingWriter {
        public HighWriter() {
            super("high");
        }
    }

    @Produces(MediaType.APPLICATION_JSON)
    public static final class JsonThingWriter extends ThingWriter {
        public JsonThingWriter() {
            super("json");
        }
    }

    @Produces(MediaType.TEXT_PLAIN)
    public static final class TextObjectWriter implements MessageBodyWriter<Object> {

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public void writeTo(Object value, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            write("object:" + ((Thing) value).value(), entityStream);
        }
    }

    @ConstrainedTo(RuntimeType.SERVER)
    public static final class ServerInterceptor implements WriterInterceptor {

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.setEntity(context.getEntity() + "!");
            context.proceed();
        }
    }

    /**
     * Writes the context of the context resolver of strings for the media type.
     */
    public static final class ContextWriter implements MessageBodyWriter<Thing> {

        @Context
        Providers providers;

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public void writeTo(Thing thing, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            ContextResolver<String> resolver = providers.getContextResolver(String.class, mediaType);
            write(resolver == null ? "none" : resolver.getContext(Thing.class), entityStream);
        }
    }

    /**
     * Reads the context of the context resolver of strings for the media type.
     */
    public static final class ContextReader implements MessageBodyReader<Thing> {

        @Context
        Providers providers;

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public Thing readFrom(Class<Thing> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
            ContextResolver<String> resolver = providers.getContextResolver(String.class, mediaType);
            return new Thing(resolver == null ? "none" : resolver.getContext(Thing.class));
        }
    }

    @Produces(MediaType.APPLICATION_JSON)
    public static final class JsonResolver implements ContextResolver<String> {
        @Override
        public String getContext(Class<?> type) {
            return "json";
        }
    }

    @Produces(MediaType.APPLICATION_XML)
    public static final class XmlResolver implements ContextResolver<String> {
        @Override
        public String getContext(Class<?> type) {
            return "xml";
        }
    }
}
