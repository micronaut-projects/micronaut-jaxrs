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

import io.micronaut.http.HttpHeaderValues;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.security.cert.Certificate;
import java.util.Objects;

/**
 *
 * Implementation of the JAX-RS {@code SecurityContext} interface.
 *
 * @author graemerocher
 * @since 3.1.0
 */
public class SimpleSecurityContextImpl implements SecurityContext {

    private final HttpRequest<?> request;

    /**
     * Default constructor.
     *
     * @param request The request object
     */
    protected SimpleSecurityContextImpl(HttpRequest<?> request) {
        this.request = Objects.requireNonNull(request, "Request cannot be null");
    }

    @Override
    public Principal getUserPrincipal() {
        return request.getUserPrincipal().orElse(null);
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public boolean isSecure() {
        return request.isSecure();
    }

    @Override
    public String getAuthenticationScheme() {
        Certificate cert = request.getCertificate().orElse(null);
        if (cert != null) {
            return CLIENT_CERT_AUTH;
        }
        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(HttpHeaderValues.AUTHORIZATION_PREFIX_BASIC)) {
            return BASIC_AUTH;
        }
        return null;
    }

}
