package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The entity is written to the stream a writer interceptor installs, within proceed(), and the
 * stream is closed after the chain, e.g. to finish a compression.
 */
@MicronautTest
@Property(name = "spec.name", value = "WriterInterceptorStreamTest")
class WriterInterceptorStreamTest {

    @Inject
    @Client("/api/writer-stream")
    HttpClient client;

    @Test
    void gzipInterceptor() throws IOException {
        byte[] body = client.toBlocking().retrieve(HttpRequest.GET("/gzip"), byte[].class);
        try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            assertEquals("hello".repeat(1000), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void appendAfterProceed() {
        assertEquals("hello:footer", client.toBlocking().retrieve(HttpRequest.GET("/footer"), String.class));
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Gzip {
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Footer {
    }

    @Requires(property = "spec.name", value = "WriterInterceptorStreamTest")
    @Provider
    @Gzip
    public static class GzipInterceptor implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.setOutputStream(new GZIPOutputStream(context.getOutputStream()));
            context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "WriterInterceptorStreamTest")
    @Provider
    @Footer
    public static class FooterInterceptor implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.proceed();
            context.getOutputStream().write(":footer".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "WriterInterceptorStreamTest")
    @Path("/writer-stream")
    public static class WriterStreamResource {

        @GET
        @Path("gzip")
        @Gzip
        @Produces(MediaType.APPLICATION_OCTET_STREAM)
        public String gzip() {
            return "hello".repeat(1000);
        }

        @GET
        @Path("footer")
        @Footer
        @Produces(MediaType.TEXT_PLAIN)
        public String footer() {
            return "hello";
        }
    }
}
