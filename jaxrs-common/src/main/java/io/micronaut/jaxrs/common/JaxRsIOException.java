/*
 * Copyright 2017-2020 original authors
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

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.codec.CodecException;

import java.io.IOException;

/**
 * The wrapper for {@link IOException} thrown in Micronaut Body readers / writers.
 *
 * @author Denis Stepanov
 * @since 4.9
 */
@Internal
public final class JaxRsIOException extends CodecException {

    private final boolean writing;

    public JaxRsIOException(String message, IOException cause) {
        this(message, cause, false);
    }

    @SuppressWarnings("NullAway") // the message of the cause can be null, which RuntimeException accepts
    public JaxRsIOException(IOException cause) {
        this(cause.getMessage(), cause, false);
    }

    private JaxRsIOException(String message, IOException cause, boolean writing) {
        super(message, cause);
        this.writing = writing;
    }

    /**
     * The {@link IOException} of a message body writer or a writer interceptor: unmapped, it is a
     * server error (JAX-RS 4.2.4), while one of a reader is the client's.
     *
     * @param message The message
     * @param cause   The exception
     * @return The wrapper
     */
    public static JaxRsIOException writing(String message, IOException cause) {
        return new JaxRsIOException(message, cause, true);
    }

    /**
     * The {@link IOException} of a message body writer or a writer interceptor, see {@link #writing(String, IOException)}.
     *
     * @param cause The exception
     * @return The wrapper
     */
    @SuppressWarnings("NullAway") // the message of the cause can be null, which RuntimeException accepts
    public static JaxRsIOException writing(IOException cause) {
        return new JaxRsIOException(cause.getMessage(), cause, true);
    }

    /**
     * @return Whether a message body writer or a writer interceptor threw it
     */
    public boolean isWriting() {
        return writing;
    }
}
