/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * The implementation of {@link WebTarget}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsWebTarget implements WebTarget, JaxRsConfigurable<WebTarget> {

    private final JaxRsClient client;
    private final UriBuilder uriBuilder;
    private final JaxRsConfiguration configuration;

    JaxRsWebTarget(JaxRsClient client, UriBuilder uriBuilder, JaxRsConfiguration configuration) {
        this.client = client;
        this.uriBuilder = uriBuilder;
        this.configuration = configuration;
    }

    @Override
    public WebTarget self() {
        return this;
    }

    @Override
    public JaxRsConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public URI getUri() {
        return uriBuilder.build();
    }

    @Override
    public UriBuilder getUriBuilder() {
        return uriBuilder;
    }

    @Override
    public WebTarget path(String path) {
        Objects.requireNonNull(path, "Path cannot be null");
        return new JaxRsWebTarget(
            client,
            uriBuilder.clone().path(path),
            configuration
        );
    }

    @Override
    public WebTarget resolveTemplate(String name, Object value) {
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        return new JaxRsWebTarget(
            client,
            uriBuilder.resolveTemplate(name, value),
            configuration
        );
    }

    @Override
    public WebTarget resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        return new JaxRsWebTarget(
            client,
            uriBuilder.clone().resolveTemplate(name, value, encodeSlashInPath),
            configuration
        );
    }

    @Override
    public WebTarget resolveTemplateFromEncoded(String name, Object value) {
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        return new JaxRsWebTarget(
            client,
            uriBuilder.clone().resolveTemplateFromEncoded(name, value),
            configuration
        );
    }

    @Override
    public WebTarget resolveTemplates(Map<String, Object> templateValues) {
        Objects.requireNonNull(templateValues, "Template values cannot be null");
        if (templateValues.isEmpty()) {
            return this;
        }
        checkForNullKeysOrValues(templateValues);
        return new JaxRsWebTarget(
            client,
            uriBuilder.clone().resolveTemplates(templateValues),
            configuration
        );
    }

    @Override
    public WebTarget resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) {
        Objects.requireNonNull(templateValues, "Template values cannot be null");
        if (templateValues.isEmpty()) {
            return this;
        }
        checkForNullKeysOrValues(templateValues);
        return new JaxRsWebTarget(
            client,
            uriBuilder.clone().resolveTemplates(templateValues, encodeSlashInPath),
            configuration
        );
    }

    @Override
    public WebTarget resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        Objects.requireNonNull(templateValues, "Template values cannot be null");
        if (templateValues.isEmpty()) {
            return this;
        }
        checkForNullKeysOrValues(templateValues);
        return new JaxRsWebTarget(
            client,
            uriBuilder.clone().resolveTemplatesFromEncoded(templateValues),
            configuration
        );
    }

    @Override
    public WebTarget matrixParam(String name, Object... values) {
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(values, "Values cannot be null");
        checkForNullValues(values);
        if (values.length == 1 && values[0] == null) {
            return new JaxRsWebTarget(
                client,
                uriBuilder.clone().replaceMatrixParam(name),
                configuration
            );
        }
        return new JaxRsWebTarget(
            client,
            uriBuilder.clone().matrixParam(name, values),
            configuration
        );
    }

    @Override
    public WebTarget queryParam(String name, Object... values) {
        Objects.requireNonNull(name, "Name cannot be null");
        checkForNullValues(values);
        return new JaxRsWebTarget(
            client,
            uriBuilder.clone().queryParam(name, values),
            configuration
        );
    }

    @Override
    public Invocation.Builder request() {
        return new JaxRsInvocationBuilder(client, uriBuilder.build(), configuration);
    }

    @Override
    public Invocation.Builder request(String... acceptedResponseTypes) {
        return request().accept(acceptedResponseTypes);
    }

    @Override
    public Invocation.Builder request(MediaType... acceptedResponseTypes) {
        return request().accept(acceptedResponseTypes);
    }

    private void checkForNullKeysOrValues(Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null) {
                throw new NullPointerException("map key null");
            }
            if (e.getValue() == null) {
                throw new NullPointerException("map value null");
            }
        }
    }

    private void checkForNullValues(Object[] values) {
        if (values != null && values.length > 1) { // One null is allowed
            for (Object value : values) {
                Objects.requireNonNull(value, "Value cannot be null");
            }
        }
    }
}
