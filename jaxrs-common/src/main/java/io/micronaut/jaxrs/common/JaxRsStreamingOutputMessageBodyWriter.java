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

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The implementation of {@link MessageBodyWriter} for {@link StreamingOutput}.
 *
 * @param <T> The steaming output
 * @author Denis Stepanov
 * @since 4.6
 */
@Prototype
@Internal
public final class JaxRsStreamingOutputMessageBodyWriter<T extends StreamingOutput> implements MessageBodyWriter<T> {
    @Override
    public boolean isWriteable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        return StreamingOutput.class.isAssignableFrom(type.getType());
    }

    @Override
    public void writeTo(@NonNull Argument<T> type,
                        @NonNull MediaType mediaType,
                        T streamingOutput,
                        @NonNull MutableHeaders outgoingHeaders,
                        @NonNull OutputStream outputStream) throws CodecException {
        try {
            streamingOutput.write(outputStream);
        } catch (IOException e) {
            throw new CodecException("Cannot write", e);
        }
    }
}
