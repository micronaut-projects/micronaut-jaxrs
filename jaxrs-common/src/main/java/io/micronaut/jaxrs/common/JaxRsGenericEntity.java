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
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.GenericEntity;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * The simple variation of {@link GenericEntity}.
 *
 * @param <T> The entity type
 * @author Denis Stepanov
 * @since 4.6
 */
@Singleton
@Internal
public final class JaxRsGenericEntity<T> extends GenericEntity<T> {

    private final Annotation[] annotations;

    JaxRsGenericEntity(T entity, Annotation[] annotations) {
        super(entity, entity.getClass());
        this.annotations = annotations;
    }

    public JaxRsGenericEntity(T entity, Type genericType, Annotation[] annotations) {
        super(entity, genericType);
        this.annotations = annotations;
    }

    public Argument<T> asArgument() {
        return ArgumentUtil.from(this, annotations);
    }
}
