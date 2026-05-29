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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import jakarta.inject.Singleton;
import jakarta.ws.rs.sse.Sse;

import java.util.Optional;

/**
 * Binds {@link Sse} for {@code @Context} parameters.
 */
@Internal
@Singleton
final class JaxRsSseBinder implements TypedRequestArgumentBinder<Sse> {
    private static final Argument<Sse> ARGUMENT = Argument.of(Sse.class);

    private final Sse sse;

    JaxRsSseBinder(JaxRsSse sse) {
        this.sse = sse;
    }

    @Override
    public Argument<Sse> argumentType() {
        return ARGUMENT;
    }

    @Override
    public BindingResult<Sse> bind(ArgumentConversionContext<Sse> context, HttpRequest<?> source) {
        return () -> Optional.of(sse);
    }
}
