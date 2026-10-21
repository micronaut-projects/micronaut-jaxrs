package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The selection of the providers of the server: the ones constrained to the client are not used
 * (ConstrainedTo), the priorities a feature registers them with order them, and a reader of the
 * application precedes the standard one of a supertype (JAX-RS 4.1.3).
 */
@MicronautTest
@Property(name = "spec.name", value = "ServerProviderSelectionTest")
class ServerProviderSelectionTest {

    @Inject
    @Client("/api/provider-selection")
    HttpClient client;

    @Test
    void clientFiltersAndInterceptorsDoNotRunOnTheServer() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/text"), String.class);
        assertEquals("text", response.body());
        assertNull(response.header("X-Client"));
    }

    @Test
    void thePriorityOfAFeatureOrdersTheWriters() {
        assertEquals("feature:x", client.toBlocking().retrieve(HttpRequest.GET("/thing")));
    }

    @Test
    void theReaderOfTheApplicationReadsAnInteger() {
        assertEquals("255", client.toBlocking().retrieve(
            HttpRequest.POST("/integer", "ff").contentType(MediaType.TEXT_PLAIN)));
    }

    @Test
    void theReaderOfTheApplicationReadsAPrimitiveInt() {
        // the reader of Integer reads an int entity parameter
        assertEquals("255", client.toBlocking().retrieve(
            HttpRequest.POST("/int", "ff").contentType(MediaType.TEXT_PLAIN)));
    }

    @Test
    void thePriorityOfAFeatureOrdersTheExceptionMappers() {
        assertEquals("feature", client.toBlocking().retrieve(HttpRequest.GET("/mapped")));
    }

    @Test
    void clientExceptionMappersDoNotRunOnTheServer() {
        var e = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/client-mapped")));
        assertEquals(418, e.getStatus().getCode());
    }

    @Test
    void clientContextResolversAndParamConvertersDoNotRunOnTheServer() {
        assertEquals("none:raw", client.toBlocking().retrieve(HttpRequest.GET("/resolved?value=raw")));
    }

    public record Thing(String value) {
    }

    public static class MappedException extends RuntimeException {
    }

    public static class ClientMappedException extends WebApplicationException {
        public ClientMappedException() {
            super(418);
        }
    }

    public abstract static class NamedMapper implements ExceptionMapper<MappedException> {
        private final String name;

        NamedMapper(String name) {
            this.name = name;
        }

        @Override
        public Response toResponse(MappedException exception) {
            return Response.ok(name, MediaType.TEXT_PLAIN).build();
        }
    }

    @Requires(property = "spec.name", value = "ServerProviderSelectionTest")
    @Provider
    @Priority(100)
    public static class AnnotatedMapper extends NamedMapper {
        public AnnotatedMapper() {
            super("annotated");
        }
    }

    @Priority(5000)
    public static class FeatureMapper extends NamedMapper {
        public FeatureMapper() {
            super("feature");
        }
    }

    @Requires(property = "spec.name", value = "ServerProviderSelectionTest")
    @Provider
    @ConstrainedTo(RuntimeType.CLIENT)
    public static class ClientMapper implements ExceptionMapper<ClientMappedException> {
        @Override
        public Response toResponse(ClientMappedException exception) {
            return Response.ok("client").build();
        }
    }

    @Requires(property = "spec.name", value = "ServerProviderSelectionTest")
    @Provider
    @ConstrainedTo(RuntimeType.CLIENT)
    public static class ClientResolver implements ContextResolver<StringBuilder> {
        @Override
        public StringBuilder getContext(Class<?> type) {
            return new StringBuilder("client");
        }
    }

    @Requires(property = "spec.name", value = "ServerProviderSelectionTest")
    @Provider
    @ConstrainedTo(RuntimeType.CLIENT)
    public static class ClientConverters implements ParamConverterProvider {
        @SuppressWarnings("unchecked")
        @Override
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType != String.class) {
                return null;
            }
            return (ParamConverter<T>) new ParamConverter<String>() {
                @Override
                public String fromString(String value) {
                    return "client:" + value;
                }

                @Override
                public String toString(String value) {
                    return value;
                }
            };
        }
    }

    @Requires(property = "spec.name", value = "ServerProviderSelectionTest")
    @Provider
    @ConstrainedTo(RuntimeType.CLIENT)
    public static class ClientOnly implements ContainerResponseFilter, WriterInterceptor {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-Client", "true");
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.setEntity(context.getEntity() + "!");
            context.proceed();
        }
    }

    public abstract static class ThingWriter implements MessageBodyWriter<Thing> {
        private final String name;

        ThingWriter(String name) {
            this.name = name;
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public void writeTo(Thing thing, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write((name + ":" + thing.value()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "ServerProviderSelectionTest")
    @Provider
    @Priority(100)
    public static class AnnotatedWriter extends ThingWriter {
        public AnnotatedWriter() {
            super("annotated");
        }
    }

    @Priority(5000)
    public static class FeatureWriter extends ThingWriter {
        public FeatureWriter() {
            super("feature");
        }
    }

    @Requires(property = "spec.name", value = "ServerProviderSelectionTest")
    @Provider
    public static class PriorityFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            // the priority of the registration precedes the one of the annotation
            context.register(FeatureWriter.class, 1);
            context.register(FeatureMapper.class, 1);
            return true;
        }
    }

    @Requires(property = "spec.name", value = "ServerProviderSelectionTest")
    @Provider
    @Consumes(MediaType.TEXT_PLAIN)
    public static class HexIntegerReader implements MessageBodyReader<Integer> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Integer.class;
        }

        @Override
        public Integer readFrom(Class<Integer> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                                MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return Integer.parseInt(new String(entityStream.readAllBytes(), StandardCharsets.UTF_8), 16);
        }
    }

    @Requires(property = "spec.name", value = "ServerProviderSelectionTest")
    @Path("/provider-selection")
    public static class SelectionResource {

        @GET
        @Path("text")
        @Produces(MediaType.TEXT_PLAIN)
        public String text() {
            return "text";
        }

        @GET
        @Path("thing")
        @Produces(MediaType.TEXT_PLAIN)
        public Thing thing() {
            return new Thing("x");
        }

        @POST
        @Path("integer")
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        public String integer(Integer value) {
            return String.valueOf(value);
        }

        @POST
        @Path("int")
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        public String primitive(int value) {
            return String.valueOf(value);
        }

        @GET
        @Path("mapped")
        @Produces(MediaType.TEXT_PLAIN)
        public String mapped() {
            throw new MappedException();
        }

        @GET
        @Path("client-mapped")
        @Produces(MediaType.TEXT_PLAIN)
        public String clientMapped() {
            throw new ClientMappedException();
        }

        @GET
        @Path("resolved")
        @Produces(MediaType.TEXT_PLAIN)
        public String resolved(@Context Providers providers, @QueryParam("value") String value) {
            ContextResolver<StringBuilder> resolver = providers.getContextResolver(StringBuilder.class, MediaType.WILDCARD_TYPE);
            return (resolver == null ? "none" : resolver.getContext(String.class).toString()) + ":" + value;
        }
    }
}
