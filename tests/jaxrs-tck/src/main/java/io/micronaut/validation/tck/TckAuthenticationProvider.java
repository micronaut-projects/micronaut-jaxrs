/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.validation.tck;

import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.AuthenticationFailureReason;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.authentication.provider.HttpRequestAuthenticationProvider;

import java.util.List;
import java.util.Map;

/**
 * The users of the TCK, like the realm of a servlet container: the user of the system property
 * {@code user} has the role {@code DIRECTOR}, and the one of {@code authuser} the role
 * {@code OTHERROLE}.
 *
 * @param <B> The body type
 */
final class TckAuthenticationProvider<B> implements HttpRequestAuthenticationProvider<B> {

    private final Map<String, String> passwords = Map.of(
        System.getProperty("user", "javajoe"), System.getProperty("password", "javajoe"),
        System.getProperty("authuser", "j2ee"), System.getProperty("authpassword", "j2ee")
    );
    private final Map<String, String> roles = Map.of(
        System.getProperty("user", "javajoe"), "DIRECTOR",
        System.getProperty("authuser", "j2ee"), "OTHERROLE"
    );

    @Override
    public AuthenticationResponse authenticate(HttpRequest<B> request, AuthenticationRequest<String, String> authenticationRequest) {
        String user = authenticationRequest.getIdentity();
        String password = passwords.get(user);
        if (password == null || !password.equals(authenticationRequest.getSecret())) {
            return AuthenticationResponse.failure(AuthenticationFailureReason.CREDENTIALS_DO_NOT_MATCH);
        }
        return AuthenticationResponse.success(user, List.of(roles.get(user)));
    }
}
