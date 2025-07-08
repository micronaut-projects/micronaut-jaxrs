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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.codec.CodecException;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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

    public JaxRsInterceptedWrite(List<BeanRegistration<WriterInterceptor>> writerInterceptorsRegistrations,
                                 NameBindingPredicate nameBindingPredicate) {
        this.nameBindingPredicate = nameBindingPredicate;
        if (writerInterceptorsRegistrations.isEmpty()) {
            throw new IllegalStateException("No WriterInterceptor found");
        }
        this.writerInterceptorsRegistrations = new ArrayList<>(writerInterceptorsRegistrations);
        JaxRsUtils.sortByPriority(this.writerInterceptorsRegistrations);
    }

    public JaxRsInterceptedWrite(List<WriterInterceptor> writerInterceptors) {
        this(
            writerInterceptors.stream().map(i -> new BeanRegistration<>(null, null, i)).toList(),
            annotationMetadata -> true
        );
    }

    public final void intercept(@NonNull Argument<T> type,
                                @NonNull MediaType mediaType,
                                S state) throws CodecException {
        try {
            List<WriterInterceptor> writerInterceptors = writerInterceptorsRegistrations.stream()
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
                            JaxRsUtils.convert(ctx.getMediaType()),
                            state);
                    },
                    type,
                    JaxRsUtils.convert(mediaType),
                    state
                );
                iterator.next().aroundWriteTo(context);
            } else {
                writeToAfterInterception(
                    (Argument<Object>) type,
                    mediaType,
                    state);
            }
        } catch (IOException e) {
            throw new JaxRsIOException(e);
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
