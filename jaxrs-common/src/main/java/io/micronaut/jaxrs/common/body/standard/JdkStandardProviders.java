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
package io.micronaut.jaxrs.common.body.standard;

import io.micronaut.core.annotation.Internal;
import io.micronaut.jaxrs.common.JaxRsStandardProviders;

import java.util.List;

/**
 * The standard providers of the boxed primitive types for the client, which registers the ones of
 * the other JDK types itself.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public final class JdkStandardProviders implements JaxRsStandardProviders {

    @Override
    public List<Object> providers() {
        return List.of(
            new JaxRsNumberMessageBodyReaderWriter<>(),
            new JaxRsBooleanMessageBodyReaderWriter(),
            new JaxRsCharacterMessageBodyReaderWriter()
        );
    }
}
