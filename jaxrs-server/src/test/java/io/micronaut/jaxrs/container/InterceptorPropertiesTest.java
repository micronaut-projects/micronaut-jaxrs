package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The interceptor contexts share the properties of the request with its filter contexts.
 */
@MicronautTest
@Property(name = "spec.name", value = "InterceptorPropertiesTest")
class InterceptorPropertiesTest {

    @Inject
    @Client("/api/interceptor-properties")
    HttpClient client;

    @Test
    void interceptorsSeeTheRequestProperties() {
        // read as bytes: the reader interceptor beans also intercept the reads of this client
        HttpResponse<byte[]> response = client.toBlocking().exchange(HttpRequest.POST("/", "body").contentType(MediaType.TEXT_PLAIN_TYPE), byte[].class);
        assertEquals("body|filter", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("filter|reader", response.header("X-Properties"));
    }

    @Requires(property = "spec.name", value = "InterceptorPropertiesTest")
    @Provider
    public static class PropertyFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.setProperty("filter", "filter");
        }
    }

    @Requires(property = "spec.name", value = "InterceptorPropertiesTest")
    @Provider
    public static class PropertyReaderInterceptor implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            Object value = context.proceed();
            if (context.getType() == String.class) {
                context.setProperty("reader", "reader");
                return value + "|" + context.getProperty("filter");
            }
            return value;
        }
    }

    @Requires(property = "spec.name", value = "InterceptorPropertiesTest")
    @Provider
    public static class PropertyWriterInterceptor implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.getHeaders().putSingle("X-Properties", context.getProperty("filter") + "|" + context.getProperty("reader"));
            context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "InterceptorPropertiesTest")
    @Path("/interceptor-properties")
    public static class PropertiesResource {
        @POST
        @Consumes("text/plain")
        @Produces("text/plain")
        public String post(String entity) {
            return entity;
        }
    }
}
