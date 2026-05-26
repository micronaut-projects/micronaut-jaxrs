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
package io.micronaut.jaxrs.container;

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.io.buffer.ByteBufferFactory;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.body.ResponseBodyWriter;
import io.micronaut.http.codec.CodecException;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.io.OutputStream;
import java.util.List;

/**
 * Writes responses for routes exposed from Jakarta REST subresource locator methods.
 */
@Internal
@Singleton
final class JaxRsSubResourceLocatorWriter implements ResponseBodyWriter<Object>, Ordered {

    private final BeanContext beanContext;
    private final MessageBodyHandlerRegistry bodyHandlerRegistry;

    JaxRsSubResourceLocatorWriter(BeanContext beanContext, MessageBodyHandlerRegistry bodyHandlerRegistry) {
        this.beanContext = beanContext;
        this.bodyHandlerRegistry = bodyHandlerRegistry;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean isWriteable(Argument<Object> type, @Nullable MediaType mediaType) {
        return type.getAnnotationMetadata().hasAnnotation(JaxRsSubResourceLocator.class);
    }

    @Override
    public void writeTo(Argument<Object> type,
                        MediaType mediaType,
                        Object object,
                        MutableHeaders outgoingHeaders,
                        OutputStream outputStream) throws CodecException {
        Object result = invokeSubResourceMethod(type, object);
        result = unwrapJaxRsResponse(result, null, outgoingHeaders);
        if (result != null) {
            writeResult(result, mediaType, outgoingHeaders, outputStream);
        }
    }

    @Override
    public CloseableByteBody writePiece(ByteBodyFactory bodyFactory,
                                        HttpRequest<?> request,
                                        HttpResponse<?> response,
                                        Argument<Object> type,
                                        MediaType mediaType,
                                        Object object) throws CodecException {
        Object result = invokeSubResourceMethod(type, object);
        result = unwrapJaxRsResponse(result, response, null);
        if (result == null) {
            return bodyFactory.createEmpty();
        }
        @SuppressWarnings("unchecked")
        Argument<Object> resultType = (Argument<Object>) Argument.of(result.getClass());
        MessageBodyWriter<Object> writer = bodyHandlerRegistry.getWriter(resultType, List.of(mediaType));
        ResponseBodyWriter<Object> responseWriter = ResponseBodyWriter.wrap(writer.createSpecific(resultType));
        return responseWriter.writePiece(bodyFactory, request, response, resultType, mediaType, result);
    }

    @Override
    public ByteBuffer<?> writeTo(Argument<Object> type,
                                 MediaType mediaType,
                                 Object object,
                                 MutableHeaders outgoingHeaders,
                                 ByteBufferFactory<?, ?> bufferFactory) throws CodecException {
        Object result = invokeSubResourceMethod(type, object);
        result = unwrapJaxRsResponse(result, null, null);
        if (result == null) {
            return bufferFactory.buffer(0);
        }
        @SuppressWarnings("unchecked")
        Argument<Object> resultType = (Argument<Object>) Argument.of(result.getClass());
        return bodyHandlerRegistry.getWriter(resultType, List.of(mediaType))
            .createSpecific(resultType)
            .writeTo(resultType, mediaType, result, outgoingHeaders, bufferFactory);
    }

    private Object invokeSubResourceMethod(Argument<Object> type, Object subResource) {
        String methodName = type.getAnnotationMetadata()
            .stringValue(JaxRsSubResourceLocator.class)
            .orElseThrow(() -> new CodecException("Missing Jakarta REST subresource locator target method"));
        Class<?> resourceType = type.getAnnotationMetadata()
            .classValue(JaxRsSubResourceLocator.class, "type")
            .orElseThrow(() -> new CodecException("Missing Jakarta REST subresource locator target type"));
        BeanDefinition<?> beanDefinition = findSubResourceBeanDefinition(resourceType);
        return invoke(beanDefinition.getRequiredMethod(methodName), subResource);
    }

    private BeanDefinition<?> findSubResourceBeanDefinition(Class<?> resourceType) {
        return beanContext.getBeanDefinitions(resourceType)
            .stream()
            .filter(beanDefinition -> beanType(beanDefinition).equals(resourceType))
            .findFirst()
            .orElseThrow(() -> new CodecException("Missing Jakarta REST subresource target bean definition: " + resourceType.getName()));
    }

    private static Class<?> beanType(BeanDefinition<?> beanDefinition) {
        if (beanDefinition instanceof ProxyBeanDefinition<?> proxyBeanDefinition) {
            return proxyBeanDefinition.getTargetType();
        }
        return beanDefinition.getBeanType();
    }

    private void writeResult(Object result,
                             MediaType mediaType,
                             MutableHeaders outgoingHeaders,
                             OutputStream outputStream) {
        @SuppressWarnings("unchecked")
        Argument<Object> resultType = (Argument<Object>) Argument.of(result.getClass());
        bodyHandlerRegistry.getWriter(resultType, List.of(mediaType))
            .createSpecific(resultType)
            .writeTo(resultType, mediaType, result, outgoingHeaders, outputStream);
    }

    private static @Nullable Object unwrapJaxRsResponse(Object result,
                                                        @Nullable HttpResponse<?> response,
                                                        @Nullable MutableHeaders outgoingHeaders) {
        if (result instanceof JaxRsMutableResponse jaxRsResponse) {
            MutableHttpResponse<?> source = jaxRsResponse.getResponse();
            if (response instanceof MutableHttpResponse<?> mutableResponse) {
                mutableResponse.status(source.code(), source.reason());
                source.getAttributes().forEach(mutableResponse::setAttribute);
                copyHeaders(source, mutableResponse.getHeaders());
            } else if (outgoingHeaders != null) {
                copyHeaders(source, outgoingHeaders);
            }
            return source.getBody().orElse(null);
        }
        return result;
    }

    private static void copyHeaders(HttpResponse<?> source, MutableHeaders target) {
        source.getHeaders().forEach((name, values) -> {
            for (String value : values) {
                target.add(name, value);
            }
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable Object invoke(ExecutableMethod executableMethod, Object subResource) {
        return executableMethod.invoke(subResource);
    }
}
