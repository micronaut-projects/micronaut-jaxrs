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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
@Property(name = "spec.name", value = "ProviderSortTest")
class ProviderSortTest {

    @Inject
    @Client("/api/provider-sort")
    HttpClient client;

    @Test
    void textHtmlUsesTextWildcardProvider() {
        assertPost("text/html", "testtext/*");
    }

    @Test
    void textXmlUsesTextWildcardProvider() {
        assertPost("text/xml", "testtext/*");
    }

    @Test
    void textPlainUsesTextPlainProvider() {
        assertPost("text/plain", "testtext/plain");
    }

    @Test
    void applicationPlainUsesDefaultProvider() {
        assertPost("application/plain", "test");
    }

    private void assertPost(String mediaType, String expectedBody) {
        MediaType type = MediaType.of(mediaType);
        HttpRequest<String> request = HttpRequest.POST("", "test")
            .contentType(type)
            .accept(type);
        HttpResponse<String> response = client.toBlocking().exchange(request, String.class);

        assertEquals(expectedBody, response.body());
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Path("/provider-sort")
    static class SortResource {

        @POST
        SortBean out(SortBean bean) {
            return bean;
        }
    }

    record SortBean(String value) {
        @Override
        public String toString() {
            return value;
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    static class DefaultSortBeanProvider implements MessageBodyReader<SortBean>, MessageBodyWriter<SortBean> {

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return SortBean.class.isAssignableFrom(type);
        }

        @Override
        public SortBean readFrom(Class<SortBean> type,
                                 Type genericType,
                                 Annotation[] annotations,
                                 jakarta.ws.rs.core.MediaType mediaType,
                                 MultivaluedMap<String, String> httpHeaders,
                                 InputStream entityStream) throws IOException, WebApplicationException {
            return new SortBean(new String(entityStream.readAllBytes(), StandardCharsets.UTF_8));
        }

        @Override
        public boolean isWriteable(Class<?> type,
                                   Type genericType,
                                   Annotation[] annotations,
                                   jakarta.ws.rs.core.MediaType mediaType) {
            return SortBean.class.isAssignableFrom(type);
        }

        @Override
        public void writeTo(SortBean bean,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException, WebApplicationException {
            entityStream.write(bean.value().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    @Consumes("text/*")
    @Produces("text/*")
    static final class TextWildcardSortBeanProvider extends DefaultSortBeanProvider {

        @Override
        public SortBean readFrom(Class<SortBean> type,
                                 Type genericType,
                                 Annotation[] annotations,
                                 jakarta.ws.rs.core.MediaType mediaType,
                                 MultivaluedMap<String, String> httpHeaders,
                                 InputStream entityStream) throws IOException, WebApplicationException {
            return new SortBean(super.readFrom(type, genericType, annotations, mediaType, httpHeaders, entityStream).value() + "text");
        }

        @Override
        public void writeTo(SortBean bean,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException, WebApplicationException {
            super.writeTo(bean, type, genericType, annotations, mediaType, httpHeaders, entityStream);
            entityStream.write("/*".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    @Consumes(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
    @Produces(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
    static final class TextPlainSortBeanProvider extends DefaultSortBeanProvider {

        @Override
        public SortBean readFrom(Class<SortBean> type,
                                 Type genericType,
                                 Annotation[] annotations,
                                 jakarta.ws.rs.core.MediaType mediaType,
                                 MultivaluedMap<String, String> httpHeaders,
                                 InputStream entityStream) throws IOException, WebApplicationException {
            return new SortBean(super.readFrom(type, genericType, annotations, mediaType, httpHeaders, entityStream).value() + "text/");
        }

        @Override
        public void writeTo(SortBean bean,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException, WebApplicationException {
            super.writeTo(bean, type, genericType, annotations, mediaType, httpHeaders, entityStream);
            entityStream.write("plain".getBytes(StandardCharsets.UTF_8));
        }
    }
}
