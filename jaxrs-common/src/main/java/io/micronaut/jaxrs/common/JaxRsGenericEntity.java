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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.ws.rs.core.GenericEntity;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;

/**
 * The simple variation of {@link GenericEntity}.
 *
 * @param <T> The entity type
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsGenericEntity<T> extends GenericEntity<T> {

    private final Argument<T> argument;
    @Nullable
    private final ByteArrayOutputStream delegateEntityStream;
    @Nullable
    private final OutputStream customEntityStream;
    private final Annotation @Nullable [] annotations;

    public JaxRsGenericEntity(T entity,
                              Argument<T> argument,
                              @Nullable ByteArrayOutputStream delegateEntityStream,
                              @Nullable OutputStream customEntityStream) {
        super(entity, argument.getType());
        this.argument = argument;
        this.delegateEntityStream = delegateEntityStream;
        this.customEntityStream = customEntityStream;
        this.annotations = null;
    }

    JaxRsGenericEntity(T entity, Annotation[] annotations) {
        super(entity, entity.getClass());
        this.argument = JaxRsArgumentUtil.from(this, annotations);
        this.delegateEntityStream = null;
        this.customEntityStream = null;
        this.annotations = annotations;
    }

    /**
     * @return The annotations given with the entity, {@code Response.ok().entity(entity, annotations)},
     * or {@code null}
     */
    public Annotation @Nullable [] getAnnotations() {
        return annotations;
    }

    public Argument<T> asArgument() {
        return argument;
    }

    public @Nullable ByteArrayOutputStream getDelegateEntityStream() {
        return delegateEntityStream;
    }

    public @Nullable OutputStream getCustomEntityStream() {
        return customEntityStream;
    }
}
