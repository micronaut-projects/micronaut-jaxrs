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
package io.micronaut.jaxrs.security.bind;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.jaxrs.runtime.ext.bind.SimpleSecurityContextBinder;
import io.micronaut.jaxrs.runtime.ext.bind.SimpleSecurityContextImpl;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Optional;

/**
 * @author graemerocher
 * @since 3.1.0
 */
@Singleton
@Replaces(SimpleSecurityContextBinder.class)
public class MicronautSecurityContextBinder extends SimpleSecurityContextBinder {

    @Override
    public BindingResult<SecurityContext> bind(
        ArgumentConversionContext<SecurityContext> context,
        HttpRequest<?> source) {
        if (source.getAttributes().contains(SecurityFilter.KEY)) {
            Authentication auth = source.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class)
                    .orElse(null);
            return (auth == null) ?
                    super.bind(context, source) :
                    () -> Optional.of(new MicronautSecurityContext(auth, source));
        }
        //noinspection unchecked
        return BindingResult.UNSATISFIED;
    }

    /**
     * Extended security context implementation that takes into account Micronaut security roles.
     */
    static final class MicronautSecurityContext extends SimpleSecurityContextImpl {

        private final Authentication authentication;

        private MicronautSecurityContext(
            Authentication authentication,
            HttpRequest<?> request) {
            super(request);
            this.authentication = authentication;
        }

        @Override
        public boolean isUserInRole(String role) {
            return authentication.getRoles().contains(role);
        }

    }
}
