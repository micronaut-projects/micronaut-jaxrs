/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.jaxrs.runtime.ext.bind;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Optional;

/**
 * A simple implementation the binds the JAX-RS {@code SecurityContext} without
 * support for roles.
 *
 * @author graemerocher
 * @since 3.1.0
 */
@Internal
@Singleton
public class SimpleSecurityContextBinder implements TypedRequestArgumentBinder<SecurityContext> {

    /**
     * Request attribute containing the active Jakarta REST security context.
     */
    public static final CharSequence SECURITY_CONTEXT_ATTRIBUTE = SimpleSecurityContextBinder.class.getName() + ".securityContext";

    private static final Argument<SecurityContext> ARGUMENT = Argument.of(SecurityContext.class);

    @Override
    public BindingResult<SecurityContext> bind(ArgumentConversionContext<SecurityContext> context, HttpRequest<?> source) {
        SecurityContext securityContext = source.getAttribute(SECURITY_CONTEXT_ATTRIBUTE, SecurityContext.class)
            .orElse(null);
        if (securityContext != null) {
            return () -> Optional.of(securityContext);
        }
        return () -> Optional.of(new SimpleSecurityContextImpl(source));
    }

    @Override
    public Argument<SecurityContext> argumentType() {
        return ARGUMENT;
    }
}
