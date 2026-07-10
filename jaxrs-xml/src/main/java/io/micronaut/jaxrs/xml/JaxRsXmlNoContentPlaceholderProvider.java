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
package io.micronaut.jaxrs.xml;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.jaxrs.common.JaxRsNoContentPlaceholderProvider;
import jakarta.inject.Singleton;
import jakarta.xml.bind.JAXBElement;

import javax.xml.namespace.QName;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * XML placeholders for Jakarta REST no-content entity handling.
 */
@Singleton
@Requires(classes = JAXBElement.class)
@Internal
final class JaxRsXmlNoContentPlaceholderProvider implements JaxRsNoContentPlaceholderProvider {
    private static final QName EMPTY_ELEMENT_NAME = new QName("jaxrs-empty");

    @Override
    public Optional<Supplier<?>> findPlaceholder(Argument<?> argument) {
        if (JAXBElement.class.isAssignableFrom(argument.getType())) {
            return Optional.of(() -> new JAXBElement<>(EMPTY_ELEMENT_NAME, Object.class, null));
        }
        return Optional.empty();
    }
}
