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

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JaxRsReflectionClientComponentInstantiatorTest {

    @Test
    void classRegisteredClientComponentsUseReflectionFallbackForContextInjection() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        configuration.register(ReflectionContextAwareBeanProvider.class);
        ReflectionContextAwareBean bean = new ReflectionContextAwareBean(null);
        MutableHttpRequest<ReflectionContextAwareBean> request = HttpRequest.POST("http://localhost/context", bean)
            .contentType(MediaType.TEXT_PLAIN_TYPE);

        configuration.writeBody(request, Argument.of(ReflectionContextAwareBean.class), bean);

        assertEquals("1111", new String(request.getBody(byte[].class).orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    void classRegisteredClientComponentAnnotationsUseReflectionFallbackMetadata() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        configuration.register(JsonResolver.class);
        configuration.register(TextResolver.class);

        ContextResolver<String> json = configuration.getContextResolver(String.class, jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE);
        ContextResolver<String> text = configuration.getContextResolver(String.class, jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE);

        assertEquals("json", json.getContext(String.class));
        assertEquals("text", text.getContext(String.class));
    }

    record ReflectionContextAwareBean(String value) {
    }

    public static final class ReflectionContextAwareBeanProvider implements MessageBodyReader<ReflectionContextAwareBean>, MessageBodyWriter<ReflectionContextAwareBean> {
        @Context
        Providers fieldProviders;

        @Context
        Configuration fieldConfiguration;

        private Providers methodProviders;
        private Configuration methodConfiguration;

        public void setProviders(@Context Providers providers) {
            methodProviders = providers;
        }

        @Context
        public void setConfiguration(Configuration configuration) {
            methodConfiguration = configuration;
        }

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return type == ReflectionContextAwareBean.class;
        }

        @Override
        public ReflectionContextAwareBean readFrom(Class<ReflectionContextAwareBean> type,
                                                   Type genericType,
                                                   Annotation[] annotations,
                                                   jakarta.ws.rs.core.MediaType mediaType,
                                                   MultivaluedMap<String, String> httpHeaders,
                                                   InputStream entityStream) {
            return new ReflectionContextAwareBean(mask());
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return type == ReflectionContextAwareBean.class;
        }

        @Override
        public void writeTo(ReflectionContextAwareBean reflectionContextAwareBean,
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

    @jakarta.ws.rs.Produces(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
    public static final class JsonResolver implements ContextResolver<String> {
        @Override
        public String getContext(Class<?> type) {
            return "json";
        }
    }

    @jakarta.ws.rs.Produces(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
    public static final class TextResolver implements ContextResolver<String> {
        @Override
        public String getContext(Class<?> type) {
            return "text";
        }
    }
}
