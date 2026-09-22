package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ResourceContext;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @Context} fields of a provider follow the request: readers and writers see the values of
 * the request they read or write, and an empty entity is read by the application reader.
 */
@MicronautTest
@Property(name = "spec.name", value = "ProviderContextInjectionTest")
class ProviderContextInjectionTest {

    @Inject
    @Client("/api/provider-context")
    HttpClient client;

    @Test
    void readerOfAnEmptyEntitySeesTheRequest() {
        assertEquals("/provider-context/reader 1111111",
            client.toBlocking().retrieve(HttpRequest.POST("/reader", "").contentType(MediaType.WILDCARD)));
    }

    @Test
    void writerSeesTheRequest() {
        assertEquals("/provider-context/writer 1111111",
            client.toBlocking().retrieve(HttpRequest.POST("/writer", "x").contentType(MediaType.TEXT_PLAIN)));
    }

    @Test
    void readerSeesTheAnnotationsOfTheParameter() {
        assertEquals("tagged:body",
            client.toBlocking().retrieve(HttpRequest.POST("/tagged", "body").contentType(MediaType.TEXT_XML)));
    }

    @Test
    void responseEntityIsWrittenByTheApplicationWriterOfTheAcceptedType() {
        assertEquals("<xml>body</xml>",
            client.toBlocking().retrieve(HttpRequest.GET("/xmlbody").accept(MediaType.TEXT_XML)));
    }

    public record Bean(String value) {
    }

    public record XmlEntity(String value) {
    }

    @Requires(property = "spec.name", value = "ProviderContextInjectionTest")
    @Provider
    public static class XmlEntityWriter implements MessageBodyWriter<XmlEntity> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            // the last @Path, like the writers of the TCK: the one of the resource method
            String path = null;
            for (Annotation annotation : annotations) {
                if (annotation instanceof Path p) {
                    path = p.value();
                }
            }
            return path != null && path.contains("xml") && MediaType.TEXT_XML_TYPE.isCompatible(mediaType) && type == XmlEntity.class;
        }

        @Override
        public void writeTo(XmlEntity entity, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("<xml>" + entity.value() + "</xml>").getBytes(StandardCharsets.UTF_8));
        }
    }

    public record Tagged(String value) {
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.PARAMETER)
    public @interface Tag {
        String value();
    }

    @Requires(property = "spec.name", value = "ProviderContextInjectionTest")
    @Provider
    public static class TaggedReader implements MessageBodyReader<Tagged> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            for (Annotation annotation : annotations) {
                if (annotation instanceof Tag) {
                    return type == Tagged.class;
                }
            }
            return false;
        }

        @Override
        public Tagged readFrom(Class<Tagged> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            String tag = null;
            for (Annotation annotation : annotations) {
                if (annotation instanceof Tag t) {
                    tag = t.value();
                }
            }
            return new Tagged(tag + ":" + new String(entityStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "ProviderContextInjectionTest")
    @Path("/provider-context")
    public static class ProviderContextResource {
        // created for every request
        @Context
        Providers providers;

        @POST
        @Path("reader")
        public String reader(Bean bean) {
            return bean.value();
        }

        @POST
        @Path("tagged")
        @jakarta.ws.rs.Consumes(MediaType.TEXT_XML)
        public String tagged(@Tag("tagged") Tagged tagged) {
            return tagged == null ? "null" : tagged.value();
        }

        @jakarta.ws.rs.GET
        @Path("xmlbody")
        public jakarta.ws.rs.core.Response xmlBody() {
            return jakarta.ws.rs.ext.RuntimeDelegate.getInstance().createResponseBuilder().entity(new XmlEntity("body")).build();
        }

        @POST
        @Path("writer")
        public Bean writer(String entity) {
            return new Bean(entity);
        }
    }

    @Requires(property = "spec.name", value = "ProviderContextInjectionTest")
    @Provider
    public static class BeanProvider implements MessageBodyReader<Bean>, MessageBodyWriter<Bean> {
        @Context
        UriInfo info;
        @Context
        Request request;
        @Context
        HttpHeaders headers;
        @Context
        SecurityContext security;
        @Context
        Providers providers;
        @Context
        ResourceContext resources;
        @Context
        Configuration configuration;

        private String describe() {
            Object[] values = {info, request, headers, security, providers, resources, configuration};
            StringBuilder mask = new StringBuilder();
            for (Object value : values) {
                mask.append(value == null ? '0' : '1');
            }
            return info.getPath() + " " + mask;
        }

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Bean.class;
        }

        @Override
        public Bean readFrom(Class<Bean> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                             MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws WebApplicationException {
            return new Bean(describe());
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Bean.class;
        }

        @Override
        public void writeTo(Bean bean, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(describe().getBytes(StandardCharsets.UTF_8));
        }
    }
}
