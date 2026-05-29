package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
@Property(name = "spec.name", value = "ProviderSortTest")
class ProviderSortTest {

    @Inject
    @Client("/api/provider-sort")
    HttpClient client;

    @Inject
    JaxRsContainerMessageBodyHandlerRegistry registry;

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

    @Test
    void bodyReaderCanUseRouteParameterAnnotations() {
        HttpRequest<String> request = HttpRequest.POST("/annotation-sensitive", "test")
            .contentType(MediaType.TEXT_XML_TYPE)
            .accept(MediaType.TEXT_PLAIN_TYPE);

        HttpResponse<String> response = client.toBlocking().exchange(request, String.class);

        assertEquals("test:body", response.body());
    }

    @Test
    void bodyReaderCanCreateEntityFromHeadersWhenBodyIsEmpty() {
        HttpRequest<String> request = HttpRequest.POST("/annotation-sensitive-header", "")
            .contentType(MediaType.TEXT_XML_TYPE)
            .accept(MediaType.TEXT_PLAIN_TYPE)
            .header("X-Body-Value", "test");

        HttpResponse<String> response = client.toBlocking().exchange(request, String.class);

        assertEquals("test:header", response.body());
    }

    @Test
    void bodyReaderIOExceptionUsesExceptionMapper() {
        HttpRequest<String> request = HttpRequest.POST("/annotation-sensitive-ioexception", "")
            .contentType(MediaType.TEXT_XML_TYPE)
            .accept(MediaType.TEXT_PLAIN_TYPE);

        HttpResponse<String> response = client.toBlocking().exchange(request, String.class);

        assertEquals(HttpStatus.ACCEPTED, response.status());
    }

    @Test
    void readerCacheIncludesArgumentAnnotations() {
        List<MediaType> mediaTypes = List.of(MediaType.TEXT_XML_TYPE);

        assertTrue(registry.findReader(Argument.of(AnnotationSensitiveBean.class), mediaTypes).isEmpty());
        assertTrue(registry.findReader(annotatedBodyArgument(), mediaTypes).isPresent());
    }

    @Test
    void readerLookupUsesConcreteProviderAfterInterceptorTypeMutation() {
        var reader = registry.findReader(Argument.of(ArrayList.class), List.of(MediaType.TEXT_PLAIN_TYPE));

        assertTrue(reader.isPresent());
        ArrayList<?> value = reader.get().read(
            Argument.of(ArrayList.class),
            MediaType.TEXT_PLAIN_TYPE,
            HttpRequest.GET("/").getHeaders(),
            new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(List.of("array-list"), value);
    }

    @Test
    void readerLookupReevaluatesDynamicIsReadable() {
        List<MediaType> mediaTypes = List.of(MediaType.of("application/dynamic"));
        DynamicReader.readable = false;
        try {
            var fallback = registry.findReader(Argument.of(DynamicBean.class), mediaTypes);
            assertTrue(fallback.isPresent());
            assertEquals("fallback", fallback.get().read(
                Argument.of(DynamicBean.class),
                mediaTypes.get(0),
                HttpRequest.GET("/").getHeaders(),
                new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8))
            ).value());

            DynamicReader.readable = true;
            var dynamic = registry.findReader(Argument.of(DynamicBean.class), mediaTypes);
            assertTrue(dynamic.isPresent());
            assertEquals("dynamic", dynamic.get().read(
                Argument.of(DynamicBean.class),
                mediaTypes.get(0),
                HttpRequest.GET("/").getHeaders(),
                new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8))
            ).value());
        } finally {
            DynamicReader.readable = false;
        }
    }

    @Test
    void boundedGenericProviderIsPrunedBeforeDynamicChecks() {
        List<MediaType> mediaTypes = List.of(MediaType.of("application/bounded-number"));
        BoundedNumberProvider.readableChecks = 0;
        BoundedNumberProvider.writeableChecks = 0;

        assertTrue(registry.findReader(Argument.of(UnsupportedBean.class), mediaTypes).isEmpty());
        assertTrue(registry.findWriter(Argument.of(UnsupportedBean.class), mediaTypes).isEmpty());
        assertEquals(0, BoundedNumberProvider.readableChecks);
        assertEquals(0, BoundedNumberProvider.writeableChecks);

        assertTrue(registry.findReader(Argument.of(Integer.class), mediaTypes).isPresent());
        assertTrue(registry.findWriter(Argument.of(Integer.class), mediaTypes).isPresent());
        assertEquals(1, BoundedNumberProvider.readableChecks);
        assertEquals(1, BoundedNumberProvider.writeableChecks);
    }

    @Test
    void jaxRsByteArrayProviderOverridesBuiltInReader() {
        HttpRequest<String> request = HttpRequest.POST("/byte-array-override", "ignored")
            .contentType(MediaType.APPLICATION_XML_TYPE)
            .accept(MediaType.APPLICATION_XML_TYPE);

        HttpResponse<String> response = client.toBlocking().exchange(request, String.class);

        assertEquals("custom-byte-array-reader:custom-byte-array-writer", response.body());
    }

    @Test
    void jaxRsMapProviderOverridesBuiltInFormReader() {
        HttpRequest<String> request = HttpRequest.POST("/map-override", "ignored=body")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
            .accept(MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        HttpResponse<String> response = client.toBlocking().exchange(request, String.class);

        assertEquals("custom-map-reader:custom-map-writer", response.body());
    }

    @Test
    void unsupportedEntityMediaTypeReturns415() {
        HttpRequest<String> request = HttpRequest.POST("/unsupported-entity", "ignored")
            .contentType(MediaType.of("abc/def"));

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
            client.toBlocking().exchange(request, String.class));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatus());
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

        @POST
        @Path("/annotation-sensitive")
        @Consumes(jakarta.ws.rs.core.MediaType.TEXT_XML)
        @Produces(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
        String annotationSensitive(@BodyMarker("body") AnnotationSensitiveBean bean) {
            return bean.value();
        }

        @POST
        @Path("/annotation-sensitive-header")
        @Consumes(jakarta.ws.rs.core.MediaType.TEXT_XML)
        @Produces(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
        String annotationSensitiveHeader(@BodyMarker("header") AnnotationSensitiveBean bean) {
            return bean == null ? "missing" : bean.value();
        }

        @POST
        @Path("/annotation-sensitive-ioexception")
        @Consumes(jakarta.ws.rs.core.MediaType.TEXT_XML)
        @Produces(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
        Response annotationSensitiveIOException(@BodyMarker("ioexception") AnnotationSensitiveBean bean) {
            return Response.ok().build();
        }

        @POST
        @Path("/byte-array-override")
        @Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_XML)
        @Produces(jakarta.ws.rs.core.MediaType.APPLICATION_XML)
        byte[] byteArrayOverride(byte[] body) {
            return body;
        }

        @POST
        @Path("/map-override")
        @Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED)
        @Produces(jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED)
        MultivaluedMap<String, String> mapOverride(MultivaluedMap<String, String> body) {
            return body;
        }

        @POST
        @Path("/unsupported-entity")
        String unsupportedEntity(UnsupportedBean body) {
            return body.value();
        }
    }

    record SortBean(String value) {
        @Override
        public String toString() {
            return value;
        }
    }

    record AnnotationSensitiveBean(String value) {
    }

    record DynamicBean(String value) {
    }

    record UnsupportedBean(String value) {
    }

    private static Argument<AnnotationSensitiveBean> annotatedBodyArgument() {
        MutableAnnotationMetadata annotationMetadata = new MutableAnnotationMetadata();
        annotationMetadata.addAnnotation(BodyMarker.class.getName(), Map.of(AnnotationMetadata.VALUE_MEMBER, "body"));
        return Argument.of(AnnotationSensitiveBean.class, annotationMetadata);
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
    @Consumes(jakarta.ws.rs.core.MediaType.TEXT_XML)
    static final class AnnotationSensitiveBeanProvider implements MessageBodyReader<AnnotationSensitiveBean> {

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return AnnotationSensitiveBean.class.isAssignableFrom(type)
                && jakarta.ws.rs.core.MediaType.TEXT_XML_TYPE.isCompatible(mediaType)
                && !bodyMarker(annotations).isEmpty();
        }

        @Override
        public AnnotationSensitiveBean readFrom(Class<AnnotationSensitiveBean> type,
                                                Type genericType,
                                                Annotation[] annotations,
                                                jakarta.ws.rs.core.MediaType mediaType,
                                                MultivaluedMap<String, String> httpHeaders,
                                                InputStream entityStream) throws IOException, WebApplicationException {
            String marker = bodyMarker(annotations);
            if (marker.equals("header")) {
                return new AnnotationSensitiveBean(httpHeaders.getFirst("X-Body-Value") + ":" + marker);
            }
            if (marker.equals("ioexception")) {
                throw new IOException("test");
            }
            return new AnnotationSensitiveBean(new String(entityStream.readAllBytes(), StandardCharsets.UTF_8) + ":" + marker);
        }

        private static String bodyMarker(Annotation[] annotations) {
            for (Annotation annotation : annotations) {
                if (annotation instanceof BodyMarker bodyMarker) {
                    return bodyMarker.value();
                }
            }
            return "";
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    static final class TestIOExceptionMapper implements ExceptionMapper<IOException> {

        @Override
        public Response toResponse(IOException exception) {
            return Response.status(Response.Status.ACCEPTED).build();
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    @Consumes("application/bounded-number")
    @Produces("application/bounded-number")
    static final class BoundedNumberProvider<T extends Number> implements MessageBodyReader<T>, MessageBodyWriter<T> {
        static int readableChecks;
        static int writeableChecks;

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            readableChecks++;
            return Number.class.isAssignableFrom(type);
        }

        @Override
        public T readFrom(Class<T> type,
                          Type genericType,
                          Annotation[] annotations,
                          jakarta.ws.rs.core.MediaType mediaType,
                          MultivaluedMap<String, String> httpHeaders,
                          InputStream entityStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWriteable(Class<?> type,
                                   Type genericType,
                                   Annotation[] annotations,
                                   jakarta.ws.rs.core.MediaType mediaType) {
            writeableChecks++;
            return Number.class.isAssignableFrom(type);
        }

        @Override
        public void writeTo(T number,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) {
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    @Consumes(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
    static final class ArrayListProvider implements MessageBodyReader<ArrayList<String>> {

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return ArrayList.class.isAssignableFrom(type)
                && jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE.isCompatible(mediaType);
        }

        @Override
        public ArrayList<String> readFrom(Class<ArrayList<String>> type,
                                          Type genericType,
                                          Annotation[] annotations,
                                          jakarta.ws.rs.core.MediaType mediaType,
                                          MultivaluedMap<String, String> httpHeaders,
                                          InputStream entityStream) {
            return new ArrayList<>(List.of("array-list"));
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    @Consumes("application/dynamic")
    static final class DynamicReader implements MessageBodyReader<DynamicBean> {
        static boolean readable;

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return DynamicBean.class.isAssignableFrom(type)
                && new jakarta.ws.rs.core.MediaType("application", "dynamic").isCompatible(mediaType)
                && readable;
        }

        @Override
        public DynamicBean readFrom(Class<DynamicBean> type,
                                    Type genericType,
                                    Annotation[] annotations,
                                    jakarta.ws.rs.core.MediaType mediaType,
                                    MultivaluedMap<String, String> httpHeaders,
                                    InputStream entityStream) {
            return new DynamicBean("dynamic");
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    @Consumes(jakarta.ws.rs.core.MediaType.WILDCARD)
    static final class DynamicFallbackReader implements MessageBodyReader<DynamicBean> {

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return DynamicBean.class.isAssignableFrom(type);
        }

        @Override
        public DynamicBean readFrom(Class<DynamicBean> type,
                                    Type genericType,
                                    Annotation[] annotations,
                                    jakarta.ws.rs.core.MediaType mediaType,
                                    MultivaluedMap<String, String> httpHeaders,
                                    InputStream entityStream) {
            return new DynamicBean("fallback");
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    @Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_XML)
    @Produces(jakarta.ws.rs.core.MediaType.APPLICATION_XML)
    static final class ByteArrayOverrideProvider implements MessageBodyReader<byte[]>, MessageBodyWriter<byte[]> {

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return type == byte[].class;
        }

        @Override
        public byte[] readFrom(Class<byte[]> type,
                               Type genericType,
                               Annotation[] annotations,
                               jakarta.ws.rs.core.MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders,
                               InputStream entityStream) {
            return "custom-byte-array-reader".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean isWriteable(Class<?> type,
                                   Type genericType,
                                   Annotation[] annotations,
                                   jakarta.ws.rs.core.MediaType mediaType) {
            return type == byte[].class;
        }

        @Override
        public void writeTo(byte[] bytes,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException {
            entityStream.write(bytes);
            entityStream.write(":custom-byte-array-writer".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "ProviderSortTest")
    @Provider
    @Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED)
    static final class MapOverrideProvider implements MessageBodyReader<MultivaluedMap<String, String>>, MessageBodyWriter<MultivaluedMap<String, String>> {

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return MultivaluedMap.class.isAssignableFrom(type);
        }

        @Override
        public MultivaluedMap<String, String> readFrom(Class<MultivaluedMap<String, String>> type,
                                                       Type genericType,
                                                       Annotation[] annotations,
                                                       jakarta.ws.rs.core.MediaType mediaType,
                                                       MultivaluedMap<String, String> httpHeaders,
                                                       InputStream entityStream) {
            MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
            map.add("provider", "custom-map-reader");
            return map;
        }

        @Override
        public boolean isWriteable(Class<?> type,
                                   Type genericType,
                                   Annotation[] annotations,
                                   jakarta.ws.rs.core.MediaType mediaType) {
            return MultivaluedMap.class.isAssignableFrom(type);
        }

        @Override
        public void writeTo(MultivaluedMap<String, String> map,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException {
            entityStream.write(map.getFirst("provider").getBytes(StandardCharsets.UTF_8));
            entityStream.write(":custom-map-writer".getBytes(StandardCharsets.UTF_8));
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
