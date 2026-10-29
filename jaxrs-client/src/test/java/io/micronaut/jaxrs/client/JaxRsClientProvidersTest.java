package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aProviderRegisteredForNoContractIsIgnored() {
        String echoed = echo()
            .register(new HighWriter(), Map.of())
            .register(new ThingWriter("low"), 5000)
            .request()
            .post(Entity.text(new Thing("x")), String.class);

        assertEquals("text/plain|low:x", echoed);
    }

    @Test
    void theStandardStringWriterIsAProvider() {
        String echoed = echo()
            .register(new StringDelegatingWriter())
            .request()
            .post(Entity.text(new Thing("x")), String.class);

        assertEquals("text/plain|delegated:x", echoed);
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
    void aSharedFilterIsInjectedWithTheConfigurationOfTheInvocation() {
        var filter = new MarkerFilter();
        client.register(filter);
        WebTarget a = echo().property("marker", "A");
        WebTarget b = echo().property("marker", "B");
        a.request().post(Entity.text("x"), String.class);
        assertEquals("A", filter.injected);
        b.request().post(Entity.text("x"), String.class);
        assertEquals("B", filter.injected);
        assertEquals("B", filter.injectedInResponse);
    }

    @Test
    void theDataOfAnEventIsReadWithTheProvidersOfTheTarget() {
        client.register(new ContextReader());
        // a target derived after the reader was injected, with another provider
        JaxRsWebTarget target = (JaxRsWebTarget) echo().register(XmlResolver.class);
        var event = new JaxRsInboundSseEvent(null, null, null, InboundSseEvent.RECONNECT_NOT_SET, "x", target.getConfiguration());

        assertEquals(new Thing("xml"), event.readData(Thing.class, MediaType.APPLICATION_XML_TYPE));
    }

    @Test
    void aResolverOfAnUnknownContextTypeHasNoContextOfAnotherType() {
        String echoed = echo()
            .register(new IntegerContextWriter())
            .register(RawResolver.class)
            .request()
            .post(Entity.entity(new Thing("x"), MediaType.APPLICATION_XML), String.class);

        // with reflection its type argument is Object, which is not Integer: no resolver; with the
        // metadata only it is not known: a resolver that has no context of the type, not a
        // ClassCastException
        assertTrue(echoed.equals("application/xml|none") || echoed.equals("application/xml|null"), echoed);
    }

    @Test
    void theReaderOfTheDataOfAnEventIsSelectedWithTheProvidersOfTheTarget() {
        client.register(new ContextSelectedReader());
        // only the target has the resolver the reader needs to read
        JaxRsWebTarget target = (JaxRsWebTarget) echo().register(XmlResolver.class);
        // the shared reader is injected again by a target without that resolver
        JaxRsWebTarget other = (JaxRsWebTarget) echo().register(JsonResolver.class);
        var otherEvent = new JaxRsInboundSseEvent(null, null, null, InboundSseEvent.RECONNECT_NOT_SET, "x", other.getConfiguration());
        assertThrows(ProcessingException.class, () -> otherEvent.readData(Thing.class, MediaType.APPLICATION_XML_TYPE));
        var event = new JaxRsInboundSseEvent(null, null, null, InboundSseEvent.RECONNECT_NOT_SET, "x", target.getConfiguration());

        assertEquals(new Thing("selected"), event.readData(Thing.class, MediaType.APPLICATION_XML_TYPE));
    }

    @Test
    void theReaderOfTheHighestPriorityIsSelectedBeforeTheNearestType() {
        String read = client.target(server.getURI()).path("client").path("hello")
            .register(new PlainStringReader(), 9000)
            .register(new PlainObjectReader(), 1)
            .request()
            .get(String.class);

        assertEquals("object", read);
    }

    @Test
    void theProvidersSelectTheReaderOfTheHighestPriority() {
        byte[] echoed = echo()
            .register(new PlainStringReader(), 9000)
            .register(new PlainObjectReader(), 1)
            .register(new ReaderLookupWriter())
            .request()
            // not read with the readers of strings
            .post(Entity.text(new Thing("x")), byte[].class);

        assertEquals("text/plain|PlainObjectReader", new String(echoed, StandardCharsets.UTF_8));
    }

    @Test
    void anEntityWithoutAContentTypeIsReadAsAnOctetStream() {
        String read = client.target(server.getURI()).path("client").path("hello")
            .register((ClientResponseFilter) (request, response) -> response.getHeaders().remove(HttpHeaders.CONTENT_TYPE))
            .register(new PlainStringReader(), 1)
            .register(new OctetStringReader(), 9000)
            .request()
            .get(String.class);

        assertEquals("octet", read);
    }

    @Consumes(MediaType.TEXT_PLAIN)
    public static final class PlainStringReader implements MessageBodyReader<String> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == String.class;
        }

        @Override
        public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
            return "string";
        }
    }

    @Consumes(MediaType.TEXT_PLAIN)
    public static final class PlainObjectReader implements MessageBodyReader<Object> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == String.class;
        }

        @Override
        public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
            return "object";
        }
    }

    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public static final class OctetStringReader implements MessageBodyReader<String> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == String.class;
        }

        @Override
        public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
            return "octet";
        }
    }

    /**
     * Writes the class of the reader of strings the providers select.
     */
    public static final class ReaderLookupWriter implements MessageBodyWriter<Thing> {

        @Context
        Providers providers;

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public void writeTo(Thing thing, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            write(providers.getMessageBodyReader(String.class, String.class, annotations, mediaType).getClass().getSimpleName(), entityStream);
        }
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

    /**
     * Writes a thing with the standard writer of strings, from the providers.
     */
    public static final class StringDelegatingWriter implements MessageBodyWriter<Thing> {

        @Context
        Providers providers;

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public void writeTo(Thing thing, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            MessageBodyWriter<String> writer = providers.getMessageBodyWriter(String.class, String.class, annotations, mediaType);
            writer.writeTo("delegated:" + thing.value(), String.class, String.class, annotations, mediaType, httpHeaders, entityStream);
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

    /**
     * Reads a thing only when a context resolver of strings is current for the media type.
     */
    public static final class ContextSelectedReader implements MessageBodyReader<Thing> {

        @Context
        Providers providers;

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class && providers.getContextResolver(String.class, mediaType) != null;
        }

        @Override
        public Thing readFrom(Class<Thing> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
            return new Thing("selected");
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

    /**
     * Writes the context of the context resolver of integers for the media type.
     */
    public static final class IntegerContextWriter implements MessageBodyWriter<Thing> {

        @Context
        Providers providers;

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public void writeTo(Thing thing, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            ContextResolver<Integer> resolver = providers.getContextResolver(Integer.class, mediaType);
            Integer context = resolver == null ? null : resolver.getContext(Thing.class);
            write(resolver == null ? "none" : String.valueOf(context), entityStream);
        }
    }

    /**
     * A resolver without a type argument: its context type is not known.
     */
    @SuppressWarnings("rawtypes")
    public static final class RawResolver implements ContextResolver {
        @Override
        public Object getContext(Class type) {
            return "raw";
        }
    }

    static class MarkerFilter implements ClientRequestFilter, ClientResponseFilter {
        @Context
        Configuration configuration;
        volatile Object injected;
        volatile Object injectedInResponse;

        @Override
        public void filter(ClientRequestContext requestContext) {
            injected = configuration.getProperty("marker");
        }

        @Override
        public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
            injectedInResponse = configuration.getProperty("marker");
        }
    }

}
