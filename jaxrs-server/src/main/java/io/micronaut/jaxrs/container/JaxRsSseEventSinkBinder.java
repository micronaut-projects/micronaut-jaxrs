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
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import jakarta.inject.Singleton;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.Optional;

/**
 * Binds request-scoped {@link SseEventSink} instances for {@code @Context} parameters.
 */
@Internal
@Singleton
final class JaxRsSseEventSinkBinder implements TypedRequestArgumentBinder<SseEventSink> {
    private static final Argument<SseEventSink> ARGUMENT = Argument.of(SseEventSink.class);

    private final JaxRsContainerMessageBodyHandlerRegistry jaxRsHandlerRegistry;
    private final MessageBodyHandlerRegistry bodyHandlerRegistry;

    JaxRsSseEventSinkBinder(JaxRsContainerMessageBodyHandlerRegistry jaxRsHandlerRegistry,
                            MessageBodyHandlerRegistry bodyHandlerRegistry) {
        this.jaxRsHandlerRegistry = jaxRsHandlerRegistry;
        this.bodyHandlerRegistry = bodyHandlerRegistry;
    }

    @Override
    public Argument<SseEventSink> argumentType() {
        return ARGUMENT;
    }

    @Override
    public BindingResult<SseEventSink> bind(ArgumentConversionContext<SseEventSink> context, HttpRequest<?> source) {
        JaxRsSseEventSink sink = source.getAttribute(JaxRsSseEventSink.ATTRIBUTE, JaxRsSseEventSink.class)
            .orElseGet(() -> {
                JaxRsSseEventSink newSink = new JaxRsSseEventSink(jaxRsHandlerRegistry, bodyHandlerRegistry);
                source.setAttribute(JaxRsSseEventSink.ATTRIBUTE, newSink);
                return newSink;
            });
        return () -> Optional.of(sink);
    }
}
