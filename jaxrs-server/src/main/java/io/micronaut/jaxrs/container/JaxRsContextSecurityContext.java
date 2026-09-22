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

import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.context.ServerRequestContext;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.function.Supplier;

/**
 * The {@link SecurityContext} injected with {@code @Context} into a bean, e.g. the
 * {@code Application} or a provider: it delegates to the security context bound for the current
 * request, by the simple binder or by micronaut-security.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
final class JaxRsContextSecurityContext implements SecurityContext {

    private static final Argument<SecurityContext> ARGUMENT = Argument.of(SecurityContext.class);

    private final Supplier<ArgumentBinder<SecurityContext, HttpRequest<?>>> binder;

    JaxRsContextSecurityContext(BeanProvider<RequestBinderRegistry> binderRegistry) {
        this.binder = SupplierUtil.memoized(() -> binderRegistry.get().findArgumentBinder(ARGUMENT)
            .orElseThrow(() -> new IllegalStateException("No binder of the JAX-RS SecurityContext")));
    }

    private SecurityContext current() {
        HttpRequest<?> request = ServerRequestContext.currentRequest()
            .orElseThrow(() -> new IllegalStateException("The SecurityContext is only available while a request is handled"));
        ArgumentConversionContext<SecurityContext> context = ConversionContext.of(ARGUMENT);
        return binder.get().bind(context, request).getValue()
            .orElseThrow(() -> new IllegalStateException("No SecurityContext for the request"));
    }

    @Override
    public Principal getUserPrincipal() {
        return current().getUserPrincipal();
    }

    @Override
    public boolean isUserInRole(String role) {
        return current().isUserInRole(role);
    }

    @Override
    public boolean isSecure() {
        return current().isSecure();
    }

    @Override
    public String getAuthenticationScheme() {
        return current().getAuthenticationScheme();
    }
}
