package example.registration;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.jaxrs.container.RestApplication;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The provider instances of {@code Application.getSingletons()} are the providers of the
 * application (JAX-RS 2.3.2), not beans of their classes, and the providers an application that
 * lists its classes does not list are not registered.
 */
@MicronautTest
@Property(name = "spec.name", value = "ApplicationProviderInstancesTest")
class ApplicationProviderInstancesTest {

    @Inject
    @Client("/api/provider-instances")
    HttpClient client;

    @Test
    void filterInstance() {
        assertEquals("filter=instance", client.toBlocking().retrieve(HttpRequest.GET("/filter")));
    }

    @Test
    void exceptionMapperInstance() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/fail")));
        assertEquals(409, e.code());
        assertEquals("instance", e.getResponse().getHeaders().get("X-Mapper"));
    }

    @Test
    void paramConverterInstance() {
        assertEquals("instance:x", client.toBlocking().retrieve(HttpRequest.GET("/param?value=x")));
    }

    @Test
    void writerInstance() {
        assertEquals("written by instance", client.toBlocking().retrieve(HttpRequest.GET("/entity")));
    }

    @Test
    void providersSeeTheRegisteredInstances() {
        assertEquals("instance null", client.toBlocking().retrieve(HttpRequest.GET("/providers")));
    }

    @Test
    void unlistedFeatureIsNotConfigured() {
        assertEquals("null", client.toBlocking().retrieve(HttpRequest.GET("/feature")));
    }

    @Requires(property = "spec.name", value = "ApplicationProviderInstancesTest")
    @Replaces(RestApplication.class)
    @Singleton
    @ApplicationPath("/api")
    public static class InstancesApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(InstancesResource.class);
        }

        @Override
        public Set<Object> getSingletons() {
            ValueFilter filter = new ValueFilter();
            filter.value = "instance";
            ValueMapper mapper = new ValueMapper();
            mapper.value = "instance";
            ValueConverterProvider converter = new ValueConverterProvider();
            converter.value = "instance";
            ValueWriter writer = new ValueWriter();
            writer.value = "instance";
            return Set.of(filter, mapper, converter, writer);
        }
    }

    public static final class Value {
        final String value;

        Value(String value) {
            this.value = value;
        }
    }

    public static final class Failure extends RuntimeException {
    }

    @Requires(property = "spec.name", value = "ApplicationProviderInstancesTest")
    @Provider
    public static class ValueFilter implements ContainerRequestFilter {
        String value = "bean";

        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.getHeaders().putSingle("X-Filter", value);
        }
    }

    @Requires(property = "spec.name", value = "ApplicationProviderInstancesTest")
    @Provider
    public static class ValueMapper implements ExceptionMapper<Failure> {
        String value = "bean";

        @Override
        public Response toResponse(Failure exception) {
            return Response.status(409).header("X-Mapper", value).build();
        }
    }

    @Requires(property = "spec.name", value = "ApplicationProviderInstancesTest")
    @Provider
    public static class ValueConverterProvider implements ParamConverterProvider {
        String value = "bean";

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType != Value.class) {
                return null;
            }
            return (ParamConverter<T>) new ParamConverter<Value>() {
                @Override
                public Value fromString(String s) {
                    return new Value(value + ":" + s);
                }

                @Override
                public String toString(Value v) {
                    return v.value;
                }
            };
        }
    }

    @Requires(property = "spec.name", value = "ApplicationProviderInstancesTest")
    @Provider
    @Produces("text/plain")
    public static class ValueWriter implements MessageBodyWriter<Value> {
        String value = "bean";

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Value.class;
        }

        @Override
        public void writeTo(Value v, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            jakarta.ws.rs.core.MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws java.io.IOException {
            entityStream.write(("written by " + value).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Not listed by the application: not registered.
     */
    @Requires(property = "spec.name", value = "ApplicationProviderInstancesTest")
    @Provider
    public static class UnlistedResolver implements ContextResolver<String> {
        @Override
        public String getContext(Class<?> type) {
            return "unlisted";
        }
    }

    /**
     * Not listed by the application: not configured.
     */
    @Requires(property = "spec.name", value = "ApplicationProviderInstancesTest")
    @Provider
    public static class UnlistedFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register((ContainerRequestFilter) requestContext -> requestContext.getHeaders().putSingle("X-Feature", "configured"));
            return true;
        }
    }

    @Requires(property = "spec.name", value = "ApplicationProviderInstancesTest")
    @Path("/provider-instances")
    public static class InstancesResource {

        @Context
        Providers providers;

        @GET
        @Path("filter")
        @Produces("text/plain")
        public String filter(@jakarta.ws.rs.HeaderParam("X-Filter") String filter) {
            return "filter=" + filter;
        }

        @GET
        @Path("fail")
        public String fail() {
            throw new Failure();
        }

        @GET
        @Path("param")
        @Produces("text/plain")
        public String param(@QueryParam("value") Value value) {
            return value.value;
        }

        @GET
        @Path("entity")
        @Produces("text/plain")
        public Value entity() {
            return new Value("ignored");
        }

        @GET
        @Path("providers")
        @Produces("text/plain")
        public String providers() {
            MessageBodyWriter<Value> writer = providers.getMessageBodyWriter(Value.class, Value.class, new Annotation[0], MediaType.TEXT_PLAIN_TYPE);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try {
                writer.writeTo(new Value("x"), Value.class, Value.class, new Annotation[0], MediaType.TEXT_PLAIN_TYPE, null, out);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            String written = out.toString(StandardCharsets.UTF_8).substring("written by ".length());
            return written + " " + providers.getContextResolver(String.class, MediaType.WILDCARD_TYPE);
        }

        @GET
        @Path("feature")
        @Produces("text/plain")
        public String feature(@jakarta.ws.rs.HeaderParam("X-Feature") String feature) {
            return String.valueOf(feature);
        }
    }
}
