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

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpHeaderValues;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.security.cert.Certificate;

/**
 * Request-aware bean implementation of {@link SecurityContext}.
 */
@Internal
@Singleton
final class JaxRsContextSecurityContext implements SecurityContext {

    @Override
    public Principal getUserPrincipal() {
        return currentRequest().getUserPrincipal().orElse(null);
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public boolean isSecure() {
        return currentRequest().isSecure();
    }

    @Override
    public String getAuthenticationScheme() {
        HttpRequest<?> request = currentRequest();
        Certificate certificate = request.getCertificate().orElse(null);
        if (certificate != null) {
            return CLIENT_CERT_AUTH;
        }
        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(HttpHeaderValues.AUTHORIZATION_PREFIX_BASIC)) {
            return BASIC_AUTH;
        }
        return null;
    }

    private static HttpRequest<?> currentRequest() {
        return ServerRequestContext.currentRequest()
            .orElseThrow(() -> new IllegalStateException("Current request not available"));
    }
}
