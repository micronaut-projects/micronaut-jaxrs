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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

/**
 * The effective registrations of the providers of the server: the ones of the
 * {@code Application}, the ones its features registered, for which contracts and with which
 * priorities, and the ones that are discovered, see {@code Configuration#isRegistered} and
 * {@code Configuration#getContracts}. Queried by the provider lookups instead of the
 * {@code Configuration} beans.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public interface JaxRsProviderRegistrations {

    /**
     * @param providerClass The class of a provider
     * @return Whether it is registered, for some contracts at least
     */
    boolean isRegistered(Class<?> providerClass);

    /**
     * @param providerClass The class of a provider
     * @param contract      A contract, e.g. {@code MessageBodyReader}
     * @return Whether it is registered for the contract: registered, and for all of the contracts
     * it implements or for that one
     */
    boolean isRegistered(Class<?> providerClass, Class<?> contract);

    /**
     * @param providerClass The class of a provider
     * @param contract      A contract, e.g. {@code ExceptionMapper}
     * @return The priority it is registered with for the contract, {@code null} if it is not
     * registered for it
     */
    @Nullable Integer priority(Class<?> providerClass, Class<?> contract);
}
