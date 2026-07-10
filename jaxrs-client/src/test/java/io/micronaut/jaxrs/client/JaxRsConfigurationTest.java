/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.http.client.multipart.MultipartDataFactory;
import io.micronaut.jaxrs.common.body.standard.JaxRsStreamingOutputMessageBodyWriter;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class JaxRsConfigurationTest {

    @Test
    void formUrlEncodedWriterOutputIsSentAsRawText() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        configuration.register(new JaxRsStreamingOutputMessageBodyWriter<>());
        MutableHttpRequest<StreamingOutput> request = HttpRequest.POST("http://localhost/form", output("entity"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        configuration.writeBody(request, Argument.of(StreamingOutput.class), request.getBody().orElseThrow());

        assertEquals("entity", request.getBody(String.class).orElseThrow());
    }

    @Test
    void multipartEntityPartsUseMicronautMultipartBody() throws Exception {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        List<EntityPart> multipart = List.of(
            EntityPart.withName("field")
                .content("value")
                .mediaType(jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE)
                .build(),
            EntityPart.withName("octet")
                .content("bytes".getBytes(StandardCharsets.UTF_8))
                .mediaType(jakarta.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM_TYPE)
                .build()
        );
        MutableHttpRequest<List<EntityPart>> request = HttpRequest.POST("http://localhost/multipart", multipart)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE);

        configuration.writeBody(request, Argument.listOf(EntityPart.class), multipart);

        MultipartBody body = assertInstanceOf(MultipartBody.class, request.getBody().orElseThrow());
        List<CapturedPart> parts = body.getData(new CapturingMultipartDataFactory());
        assertEquals("field", parts.get(0).name);
        assertNull(parts.get(0).filename);
        assertEquals("value", parts.get(0).content);
        assertEquals("octet", parts.get(1).name);
        assertEquals("octet", parts.get(1).filename);
        assertEquals(MediaType.APPLICATION_OCTET_STREAM_TYPE, parts.get(1).contentType);
        assertArrayEquals("bytes".getBytes(StandardCharsets.UTF_8), (byte[]) parts.get(1).content);
    }

    @Test
    void invalidExplicitClassContractsAreIgnored() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();

        configuration.register(JaxRsStreamingOutputMessageBodyWriter.class, ClientRequestFilter.class);

        assertEquals(0, configuration.getClasses().size());
        assertEquals(0, configuration.getRequestFilters().size());
    }

    @Test
    void invalidExplicitInstanceContractsAreIgnored() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();

        configuration.register(new JaxRsStreamingOutputMessageBodyWriter<>(), Map.of(ClientRequestFilter.class, 400));

        assertEquals(0, configuration.getInstances().size());
        assertEquals(0, configuration.getRequestFilters().size());
    }

    @Test
    void mapContractPriorityOverridesClientFilterPriority() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();

        configuration.register(FirstFilter.class, Map.of(ClientRequestFilter.class, 400));
        configuration.register(SecondFilter.class, Map.of(ClientRequestFilter.class, 300));

        assertEquals(List.of(SecondFilter.class, FirstFilter.class), configuration.getRequestFilters()
            .stream()
            .map(Object::getClass)
            .toList());
    }

    @Test
    void classRegisteredClientWritersReceiveContext() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        configuration.register(ContextAwareBeanProvider.class);
        ContextAwareBean bean = new ContextAwareBean(null);
        MutableHttpRequest<ContextAwareBean> request = HttpRequest.POST("http://localhost/context", bean)
            .contentType(MediaType.TEXT_PLAIN_TYPE);

        configuration.writeBody(request, Argument.of(ContextAwareBean.class), bean);

        assertEquals("1111", new String(request.getBody(byte[].class).orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    void classRegisteredClientReadersReceiveContext() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        configuration.register(ContextAwareBeanProvider.class);
        HttpResponse<byte[]> response = HttpResponse.ok("ignored".getBytes(StandardCharsets.UTF_8))
            .contentType(MediaType.TEXT_PLAIN_TYPE);

        ContextAwareBean bean = configuration.createHttpMessageEntityReader().readEntity(response, Argument.of(ContextAwareBean.class));

        assertEquals("1111", bean.value());
    }

    private static StreamingOutput output(String value) {
        return outputStream -> outputStream.write(value.getBytes(StandardCharsets.UTF_8));
    }

    @Introspected
    public static final class FirstFilter implements ClientRequestFilter {
        @Override
        public void filter(ClientRequestContext requestContext) throws IOException {
        }
    }

    @Introspected
    public static final class SecondFilter implements ClientRequestFilter {
        @Override
        public void filter(ClientRequestContext requestContext) throws IOException {
        }
    }

    record ContextAwareBean(String value) {
    }

    private static final class CapturingMultipartDataFactory implements MultipartDataFactory<CapturedPart> {
        @Override
        public CapturedPart createFileUpload(String name,
                                             String filename,
                                             MediaType contentType,
                                             String encoding,
                                             Charset charset,
                                             long length) {
            return new CapturedPart(name, filename, contentType, null);
        }

        @Override
        public CapturedPart createAttribute(String name, String value) {
            return new CapturedPart(name, null, null, value);
        }

        @Override
        public void setContent(CapturedPart fileUploadObject, Object content) {
            fileUploadObject.content = content;
        }
    }

    private static final class CapturedPart {
        private final String name;
        private final String filename;
        private final MediaType contentType;
        private Object content;

        private CapturedPart(String name, String filename, MediaType contentType, Object content) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.content = content;
        }
    }

    @Introspected(accessKind = { Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD }, visibility = Introspected.Visibility.ANY)
    public static final class ContextAwareBeanProvider implements MessageBodyReader<ContextAwareBean>, MessageBodyWriter<ContextAwareBean> {
        @Context
        Providers fieldProviders;

        @Context
        Configuration fieldConfiguration;

        private Providers methodProviders;
        private Configuration methodConfiguration;

        @Executable
        public void setProviders(@Context Providers providers) {
            methodProviders = providers;
        }

        @Context
        public void setConfiguration(Configuration configuration) {
            methodConfiguration = configuration;
        }

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return type == ContextAwareBean.class;
        }

        @Override
        public ContextAwareBean readFrom(Class<ContextAwareBean> type,
                                         Type genericType,
                                         Annotation[] annotations,
                                         jakarta.ws.rs.core.MediaType mediaType,
                                         MultivaluedMap<String, String> httpHeaders,
                                         InputStream entityStream) {
            return new ContextAwareBean(mask());
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return type == ContextAwareBean.class;
        }

        @Override
        public long getSize(ContextAwareBean contextAwareBean,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType) {
            return -1;
        }

        @Override
        public void writeTo(ContextAwareBean contextAwareBean,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            jakarta.ws.rs.core.MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException {
            entityStream.write(mask().getBytes(StandardCharsets.UTF_8));
        }

        private String mask() {
            return (fieldProviders == null ? "0" : "1")
                + (fieldConfiguration == null ? "0" : "1")
                + (methodProviders == null ? "0" : "1")
                + (methodConfiguration == null ? "0" : "1");
        }
    }
}
