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

import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.codec.CodecException;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The JAX-RS body write interceptor.
 *
 * @param <T> The type
 * @param <S> The context state implementation
 * @author Denis Stepanov
 * @since 4.9.0
 */
@Internal
public abstract class JaxRsInterceptedWrite<T, S extends JaxRsWriterInterceptorContextState> {

    private final List<BeanRegistration<WriterInterceptor>> writerInterceptorsRegistrations;
    private final NameBindingPredicate nameBindingPredicate;
    // the interceptors bound and ordered already, see #JaxRsInterceptedWrite(List)
    private final @Nullable List<WriterInterceptor> bound;

    public JaxRsInterceptedWrite(List<BeanRegistration<WriterInterceptor>> writerInterceptorsRegistrations,
                                NameBindingPredicate nameBindingPredicate) {
        this.nameBindingPredicate = nameBindingPredicate;
        if (writerInterceptorsRegistrations.isEmpty()) {
            throw new IllegalStateException("No WriterInterceptor found");
        }
        this.writerInterceptorsRegistrations = new ArrayList<>(writerInterceptorsRegistrations);
        JaxRsUtils.sortRegistrationsByPriority(this.writerInterceptorsRegistrations);
        this.bound = null;
    }

    /**
     * Through interceptors that are bound and ordered already, e.g. the precomputed chain of a
     * resource method, see {@link JaxRsRouteInterceptors#bound}.
     *
     * @param interceptors The interceptors, in their order, possibly none
     */
    protected JaxRsInterceptedWrite(List<WriterInterceptor> interceptors) {
        this.writerInterceptorsRegistrations = List.of();
        this.nameBindingPredicate = metadata -> true;
        this.bound = interceptors;
    }

    public final void intercept(@NonNull Argument<T> type,
                                @NonNull MediaType mediaType,
                                S state) throws CodecException {
        intercept(type, mediaType, state, new LinkedHashMap<>());
    }

    /**
     * Write through the interceptors.
     *
     * @param type       The type
     * @param mediaType  The media type
     * @param state      The state
     * @param properties The properties of the request or response the entity is written to, which
     *                   the interceptor contexts share with its filter contexts
     * @throws CodecException If the entity cannot be written
     */
    public final void intercept(@NonNull Argument<T> type,
                                @NonNull MediaType mediaType,
                                S state,
                                @NonNull Map<String, Object> properties) throws CodecException {
        try {
            List<WriterInterceptor> writerInterceptors = bound != null ? bound : writerInterceptorsRegistrations.stream()
                .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
                .map(BeanRegistration::getBean)
                .toList();
            Iterator<WriterInterceptor> iterator = writerInterceptors.iterator();
            if (iterator.hasNext()) {
                JaxRsWriterInterceptorContext context = new JaxRsWriterInterceptorContext(iterator,
                    ctx -> {
                        Argument<Object> argument = (Argument<Object>) ctx.asArgument();
                        Object newEntity = ctx.getEntity();
                        if (!argument.isInstance(newEntity)) {
                            newEntity = ConversionService.SHARED.convertRequired(newEntity, argument.getType());
                            ctx.setEntity(newEntity);
                        }
                        writeToAfterInterception(
                            argument,
                            Objects.requireNonNull(JaxRsUtils.convert(ctx.getMediaType())),
                            state);
                    },
                    type,
                    Objects.requireNonNull(JaxRsUtils.convert(mediaType)),
                    state,
                    properties
                );
                iterator.next().aroundWriteTo(context);
                state.finish();
            } else {
                writeToAfterInterception(
                    (Argument<Object>) type,
                    mediaType,
                    state);
            }
        } catch (IOException e) {
            throw JaxRsIOException.writing(e);
        }
    }

    /**
     * Write to after the interception.
     * Some of the value might have been changed.
     *
     * @param argument        The argument
     * @param mediaType       The media type
     * @param state           The state (same as passed into {@link #intercept})
     */
    protected abstract void writeToAfterInterception(@NonNull Argument<Object> argument,
                                                     @NonNull MediaType mediaType,
                                                     S state);

}
