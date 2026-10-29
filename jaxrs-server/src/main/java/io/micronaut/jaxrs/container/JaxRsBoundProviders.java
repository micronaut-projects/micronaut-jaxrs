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

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/**
 * The filters and interceptors bound to a resource method, with its effective name bindings:
 * the request filters, the response filters, the reader interceptors and the writer
 * interceptors, each in its order, built once, when first used, see
 * {@link JaxRsFeatures#bound}. A request only iterates them.
 *
 * <p>The chains of the requests that matched no resource method are the ones of the
 * {@code null} method, see {@link Key}.</p>
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
final class JaxRsBoundProviders {

    /**
     * The chain of the post-matching request filters.
     */
    static final int REQUEST_FILTERS = 0;
    /**
     * The chain of the response filters, in the reverse order of their priorities.
     */
    static final int RESPONSE_FILTERS = 1;
    /**
     * The chain of the reader interceptors.
     */
    static final int READER_INTERCEPTORS = 2;
    /**
     * The chain of the writer interceptors.
     */
    static final int WRITER_INTERCEPTORS = 3;

    private final JaxRsFeatures.Components components;
    private final AtomicReferenceArray<@Nullable Chain> chains = new AtomicReferenceArray<>(4);

    /**
     * @param components The components the dynamic features registered for the resource method
     */
    JaxRsBoundProviders(JaxRsFeatures.Components components) {
        this.components = components;
    }

    /**
     * @return The components the dynamic features registered for the resource method
     */
    JaxRsFeatures.Components components() {
        return components;
    }

    /**
     * A chain, built once: concurrent first uses may build it more than once, the first one
     * built is kept.
     *
     * @param kind    The chain, e.g. {@link #REQUEST_FILTERS}
     * @param source  The providers it is built of, e.g. the filter beans: a chain of other
     *                providers is built again, not cached
     * @param builder Builds it
     * @param <P>     The type of the providers
     * @param <C>     The type of the chain
     * @return The chain
     */
    @SuppressWarnings({"unchecked", "NullAway"}) // the chain of the builder, which may be null
    <P, C> C chain(int kind, List<P> source, Supplier<C> builder) {
        Chain chain = chains.get(kind);
        if (chain == null) {
            Chain built = new Chain(source, builder.get());
            chain = chains.compareAndExchange(kind, null, built);
            if (chain == null) {
                chain = built;
            }
        }
        if (chain.source() != source) {
            return builder.get();
        }
        return (C) chain.providers();
    }

    /**
     * A chain and the providers it was built of.
     *
     * @param source    The providers it was built of
     * @param providers The chain, possibly {@code null}
     */
    private record Chain(Object source, @Nullable Object providers) {
    }

    /**
     * The key of the chains of a request: its resource method and the name bindings of its route,
     * which the chains are bound with.
     *
     * @param resourceMethod The element of the resource method, see
     *                       {@link JaxRsRouteSupport#resourceMethodKey}, {@code null} for none
     * @param bindings       The name bindings of the route of the request
     */
    record Key(@Nullable Object resourceMethod, List<String> bindings) {
    }
}
