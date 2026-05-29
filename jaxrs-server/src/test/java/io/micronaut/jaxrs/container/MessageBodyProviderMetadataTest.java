package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(startApplication = false)
@Property(name = "spec.name", value = "MessageBodyProviderMetadataTest")
class MessageBodyProviderMetadataTest {
    private static final MediaType MATCHED_MEDIA_TYPE = MediaType.of("application/matched");

    @Inject
    JaxRsContainerMessageBodyHandlerRegistry registry;

    @BeforeEach
    void resetCounters() {
        MatchingProvider.instances = 0;
        WrongMediaProvider.instances = 0;
        WrongTypeProvider.instances = 0;
        ClientProvider.instances = 0;
    }

    @Test
    void messageBodyProviderMetadataPrunesCandidatesBeforeInstantiation() {
        List<MediaType> mediaTypes = List.of(MATCHED_MEDIA_TYPE);

        assertTrue(registry.findReader(Argument.of(MatchedBean.class), mediaTypes).isPresent());
        assertTrue(registry.findWriter(Argument.of(MatchedBean.class), mediaTypes).isPresent());

        assertEquals(1, MatchingProvider.instances);
        assertEquals(0, WrongMediaProvider.instances);
        assertEquals(0, WrongTypeProvider.instances);
        assertEquals(0, ClientProvider.instances);
    }

    record MatchedBean(String value) {
    }

    record WrongBean(String value) {
    }

    @Requires(property = "spec.name", value = "MessageBodyProviderMetadataTest")
    @Provider
    @Consumes("application/matched")
    @Produces("application/matched")
    static final class MatchingProvider implements MessageBodyReader<MatchedBean>, MessageBodyWriter<MatchedBean> {
        static int instances;

        MatchingProvider() {
            instances++;
        }

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return type == MatchedBean.class;
        }

        @Override
        public MatchedBean readFrom(Class<MatchedBean> type,
                                    Type genericType,
                                    Annotation[] annotations,
                                    jakarta.ws.rs.core.MediaType mediaType,
                                    MultivaluedMap<String, String> httpHeaders,
                                    InputStream entityStream) {
            return new MatchedBean("matched");
        }

        @Override
        public boolean isWriteable(Class<?> type,
                                   Type genericType,
                                   Annotation[] annotations,
                                   jakarta.ws.rs.core.MediaType mediaType) {
            return type == MatchedBean.class;
        }

        @Override
        public void writeTo(MatchedBean matchedBean,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws java.io.IOException {
            entityStream.write(matchedBean.value().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "MessageBodyProviderMetadataTest")
    @Provider
    @Consumes("application/other")
    @Produces("application/other")
    static final class WrongMediaProvider implements MessageBodyReader<MatchedBean>, MessageBodyWriter<MatchedBean> {
        static int instances;

        WrongMediaProvider() {
            instances++;
        }

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return true;
        }

        @Override
        public MatchedBean readFrom(Class<MatchedBean> type,
                                    Type genericType,
                                    Annotation[] annotations,
                                    jakarta.ws.rs.core.MediaType mediaType,
                                    MultivaluedMap<String, String> httpHeaders,
                                    InputStream entityStream) {
            return new MatchedBean("wrong-media");
        }

        @Override
        public boolean isWriteable(Class<?> type,
                                   Type genericType,
                                   Annotation[] annotations,
                                   jakarta.ws.rs.core.MediaType mediaType) {
            return true;
        }

        @Override
        public void writeTo(MatchedBean matchedBean,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) {
        }
    }

    @Requires(property = "spec.name", value = "MessageBodyProviderMetadataTest")
    @Provider
    @Consumes("application/matched")
    @Produces("application/matched")
    static final class WrongTypeProvider implements MessageBodyReader<WrongBean>, MessageBodyWriter<WrongBean> {
        static int instances;

        WrongTypeProvider() {
            instances++;
        }

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return true;
        }

        @Override
        public WrongBean readFrom(Class<WrongBean> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType,
                                  MultivaluedMap<String, String> httpHeaders,
                                  InputStream entityStream) {
            return new WrongBean("wrong-type");
        }

        @Override
        public boolean isWriteable(Class<?> type,
                                   Type genericType,
                                   Annotation[] annotations,
                                   jakarta.ws.rs.core.MediaType mediaType) {
            return true;
        }

        @Override
        public void writeTo(WrongBean wrongBean,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) {
        }
    }

    @Requires(property = "spec.name", value = "MessageBodyProviderMetadataTest")
    @Provider
    @ConstrainedTo(RuntimeType.CLIENT)
    @Consumes("application/matched")
    @Produces("application/matched")
    static final class ClientProvider implements MessageBodyReader<MatchedBean>, MessageBodyWriter<MatchedBean> {
        static int instances;

        ClientProvider() {
            instances++;
        }

        @Override
        public boolean isReadable(Class<?> type,
                                  Type genericType,
                                  Annotation[] annotations,
                                  jakarta.ws.rs.core.MediaType mediaType) {
            return true;
        }

        @Override
        public MatchedBean readFrom(Class<MatchedBean> type,
                                    Type genericType,
                                    Annotation[] annotations,
                                    jakarta.ws.rs.core.MediaType mediaType,
                                    MultivaluedMap<String, String> httpHeaders,
                                    InputStream entityStream) {
            return new MatchedBean("client");
        }

        @Override
        public boolean isWriteable(Class<?> type,
                                   Type genericType,
                                   Annotation[] annotations,
                                   jakarta.ws.rs.core.MediaType mediaType) {
            return true;
        }

        @Override
        public void writeTo(MatchedBean matchedBean,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) {
        }
    }
}
