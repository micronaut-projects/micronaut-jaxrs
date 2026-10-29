package example.extension;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the providers of the application are selected and handed their arguments (JAX-RS 3.3.3,
 * 4.1.3, 4.2, 4.3), and the entity streams of the request filters closed.
 */
@MicronautTest
@Property(name = "spec.name", value = "ProviderSelectionTest")
class ProviderSelectionTest {

    static final String APP = "text/x-app";

    @Inject
    @Client("/api/provider-selection")
    HttpClient client;

    @Test
    void readerOfTheHighestPriorityIsSelected() {
        assertEquals("object:value", client.toBlocking().retrieve(HttpRequest.POST("/read", "value").contentType(APP)));
    }

    @Test
    void contextResolverOfASubtypeResolves() {
        assertEquals("7", client.toBlocking().retrieve(HttpRequest.GET("/resolver")));
    }

    @Test
    void contextResolverOfASupertypeDoesNotResolve() {
        assertEquals("null", client.toBlocking().retrieve(HttpRequest.GET("/long-resolver")));
    }

    @Test
    void emptyEntityIsABadRequestTheMappersMap() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.POST("/number", "").contentType(MediaType.TEXT_PLAIN)));
        assertEquals(422, e.code());
    }

    @Test
    void writerIsGivenTheRuntimeClassAndTheDeclaredType() {
        assertEquals("java.util.ArrayList java.util.List<java.lang.String>",
            client.toBlocking().retrieve(HttpRequest.GET("/list").accept("text/x-list")));
    }

    @Test
    void writerOfTheRawAndTheGenericTypeIsSelected() {
        assertEquals("generic java.util.ArrayList java.util.List<java.lang.String>",
            client.toBlocking().retrieve(HttpRequest.GET("/generic").accept("text/x-generic")));
    }

    @Test
    void writersAfterTheSelectedOneAreNotAsked() {
        assertEquals("selected", client.toBlocking().retrieve(HttpRequest.GET("/selected").accept("text/x-selected")));
    }

    @Test
    void entityStreamOfARequestFilterIsClosed() {
        TrackedStream.closed = 0;
        assertEquals("3", client.toBlocking().retrieve(HttpRequest.POST("/stream", "abc").contentType(MediaType.TEXT_PLAIN)));
        assertEquals(1, TrackedStream.closed);
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.POST("/stream", "abc").contentType(MediaType.TEXT_PLAIN).header("X-Abort", "true")));
        assertEquals(409, e.code());
        assertEquals(2, TrackedStream.closed);
    }

    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    @Consumes(APP)
    @Priority(9000)
    public static class StringReader implements MessageBodyReader<String> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return type == String.class;
        }

        @Override
        public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return "string:" + new String(entityStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    @Consumes(APP)
    @Priority(1)
    public static class ObjectReader implements MessageBodyReader<Object> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return type == String.class;
        }

        @Override
        public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return "object:" + new String(entityStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    public static class NumberResolver implements ContextResolver<Number> {
        @Override
        public Number getContext(Class<?> type) {
            return null;
        }
    }

    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    public static class IntegerResolver implements ContextResolver<Integer> {
        @Override
        public Integer getContext(Class<?> type) {
            return 7;
        }
    }

    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    public static class ObjectResolver implements ContextResolver<Object> {
        @Override
        public Object getContext(Class<?> type) {
            return "object";
        }
    }

    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    public static class BadRequestMapper implements ExceptionMapper<BadRequestException> {
        @Override
        public Response toResponse(BadRequestException exception) {
            return Response.status(422).build();
        }
    }

    /**
     * Writes only the runtime class of an entity.
     */
    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    @Produces("text/x-list")
    @SuppressWarnings("rawtypes")
    public static class ArrayListWriter implements MessageBodyWriter<ArrayList> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return type == ArrayList.class;
        }

        @Override
        public void writeTo(ArrayList list, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write((type.getName() + " " + genericType.getTypeName()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Writes an entity of the class ArrayList declared as a List of strings.
     */
    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    @Produces("text/x-generic")
    @SuppressWarnings("rawtypes")
    public static class GenericListWriter implements MessageBodyWriter<ArrayList> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return type == ArrayList.class && genericType instanceof ParameterizedType parameterized
                && parameterized.getRawType() == List.class && parameterized.getActualTypeArguments()[0] == String.class;
        }

        @Override
        public void writeTo(ArrayList list, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("generic " + type.getName() + " " + genericType.getTypeName()).getBytes(StandardCharsets.UTF_8));
        }
    }

    public record Selected(String value) {
    }

    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    @Produces("text/x-selected")
    @Priority(1)
    public static class SelectedWriter implements MessageBodyWriter<Selected> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return type == Selected.class;
        }

        @Override
        public void writeTo(Selected selected, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(selected.value().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * A later writer, which fails when it is asked.
     */
    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    @Produces("text/x-selected")
    @Priority(9000)
    public static class FailingWriter implements MessageBodyWriter<Selected> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            throw new IllegalStateException("not asked");
        }

        @Override
        public void writeTo(Selected selected, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) {
            throw new IllegalStateException("not selected");
        }
    }

    /**
     * Replaces the annotations of every entity written, which keeps its types.
     */
    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    public static class AnnotationsInterceptor implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.setAnnotations(context.getAnnotations());
            context.proceed();
        }
    }

    public static final class TrackedStream extends ByteArrayInputStream {
        static volatile int closed;

        TrackedStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed++;
            super.close();
        }
    }

    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Provider
    public static class StreamFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            if (!requestContext.getUriInfo().getPath().endsWith("stream")) {
                return;
            }
            requestContext.setEntityStream(new TrackedStream(requestContext.getEntityStream().readAllBytes()));
            if (requestContext.getHeaderString("X-Abort") != null) {
                requestContext.abortWith(Response.status(409).build());
            }
        }
    }

    @Requires(property = "spec.name", value = "ProviderSelectionTest")
    @Path("/provider-selection")
    public static class SelectionResource {

        @Context
        Providers providers;

        @POST
        @Path("read")
        @Consumes(APP)
        @Produces("text/plain")
        public String read(String value) {
            return value;
        }

        @GET
        @Path("resolver")
        @Produces("text/plain")
        public String resolver() {
            ContextResolver<Number> resolver = providers.getContextResolver(Number.class, jakarta.ws.rs.core.MediaType.WILDCARD_TYPE);
            return String.valueOf(resolver == null ? null : resolver.getContext(Integer.class));
        }

        @GET
        @Path("long-resolver")
        @Produces("text/plain")
        public String longResolver() {
            ContextResolver<Long> resolver = providers.getContextResolver(Long.class, jakarta.ws.rs.core.MediaType.WILDCARD_TYPE);
            Long context = resolver == null ? null : resolver.getContext(String.class);
            return String.valueOf(context);
        }

        @POST
        @Path("number")
        @Consumes("text/plain")
        @Produces("text/plain")
        public String number(Integer value) {
            return String.valueOf(value);
        }

        @GET
        @Path("list")
        @Produces("text/x-list")
        public List<String> list() {
            return new ArrayList<>(List.of("a"));
        }

        @GET
        @Path("selected")
        @Produces("text/x-selected")
        public Selected selected() {
            return new Selected("selected");
        }

        @GET
        @Path("generic")
        public List<String> generic() {
            return new ArrayList<>(List.of("a"));
        }

        @POST
        @Path("stream")
        @Consumes("text/plain")
        @Produces("text/plain")
        public String stream(byte[] bytes) {
            assertTrue(bytes.length > 0);
            return String.valueOf(bytes.length);
        }
    }
}
