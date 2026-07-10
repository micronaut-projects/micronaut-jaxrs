/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.ws.rs.Produces;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * The reader remapped {@link MessageBodyWriter}.
 *
 * @param <T> The type
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
public final class JaxRsMessageBodyWriter<T> implements MessageBodyWriter<T> {

    private final List<MediaType> produces;
    private final jakarta.ws.rs.ext.MessageBodyWriter<T> delegate;

    public JaxRsMessageBodyWriter(List<MediaType> produces, jakarta.ws.rs.ext.MessageBodyWriter<T> delegate) {
        this.produces = produces;
        this.delegate = delegate;
    }

    public JaxRsMessageBodyWriter(AnnotationMetadata annotationMetadata, jakarta.ws.rs.ext.MessageBodyWriter<T> delegate) {
        this(asMediaTypes(annotationMetadata), delegate);
    }

    private static List<MediaType> asMediaTypes(AnnotationMetadata annotationMetadata) {
        AnnotationValue<Produces> producesAnnotationValue = annotationMetadata.getAnnotation(Produces.class);
        if (producesAnnotationValue == null) {
            return List.of();
        }
        return Arrays.stream(producesAnnotationValue.stringValues())
            .map(MediaType::of)
            .toList();
    }

    @Override
    public boolean isWriteable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        return delegate.isWriteable(type.getType(), type.asType(), JaxRsArgumentUtil.synthesizeAnnotations(type), JaxRsUtils.convert(mediaType));
    }

    @Override
    public void writeTo(@NonNull Argument<T> type,
                        @NonNull MediaType mediaType,
                        T object,
                        @NonNull MutableHeaders outgoingHeaders,
                        @NonNull OutputStream outputStream) throws CodecException {
        try {
            JaxRsMutableObjectHeadersMultivaluedMap httpHeaders = new JaxRsMutableObjectHeadersMultivaluedMap(outgoingHeaders);
            delegate.writeTo(object,
                type.getType(),
                type.asType(),
                JaxRsArgumentUtil.synthesizeAnnotations(type),
                JaxRsUtils.convert(mediaType),
                httpHeaders,
                outputStream
            );
            if (!httpHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
                if (mediaType == null || JaxRsUtils.convert(mediaType).isWildcardType()) {
                    if (produces.size() == 1 && isConcrete(produces.get(0))) {
                        httpHeaders.add(HttpHeaders.CONTENT_TYPE, produces.get(0).toString());
                    }
                } else {
                    httpHeaders.add(HttpHeaders.CONTENT_TYPE, mediaType.toString());
                }
            }
        } catch (IOException e) {
            throw new JaxRsIOException("Cannot write to", e);
        }
    }

    private static boolean isConcrete(MediaType mediaType) {
        return !"*".equals(mediaType.getType()) && !"*".equals(mediaType.getSubtype());
    }

}
