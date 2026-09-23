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

import java.util.List;

/**
 * Standard entity providers of JAX-RS (section 4.2.4) that a module adds, found with the
 * {@link java.util.ServiceLoader}: the client, which has no bean context, registers them.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public interface JaxRsStandardProviders {

    /**
     * @return New instances of the providers: message body readers and writers
     */
    List<Object> providers();
}
