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
        uriBuilder.path(path);
        return this;
    }

    @Override
    public WebTarget resolveTemplate(String name, Object value) {
        uriBuilder.resolveTemplate(name, value);
        return this;
    }

    @Override
    public WebTarget resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        uriBuilder.resolveTemplate(name, value, encodeSlashInPath);
        return this;
    }

    @Override
    public WebTarget resolveTemplateFromEncoded(String name, Object value) {
        uriBuilder.resolveTemplateFromEncoded(name, value);
        return this;
    }

    @Override
    public WebTarget resolveTemplates(Map<String, Object> templateValues) {
        uriBuilder.resolveTemplates(templateValues);
        return this;
    }

    @Override
    public WebTarget resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) {
        uriBuilder.resolveTemplates(templateValues, encodeSlashInPath);
        return this;
    }

    @Override
    public WebTarget resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        uriBuilder.resolveTemplatesFromEncoded(templateValues);
        return this;
    }

    @Override
    public WebTarget matrixParam(String name, Object... values) {
        uriBuilder.matrixParam(name, values);
        return this;
    }

    @Override
    public WebTarget queryParam(String name, Object... values) {
        uriBuilder.queryParam(name, values);
        return this;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JaxRsWebTarget that = (JaxRsWebTarget) o;
        return Objects.equals(uriBuilder, that.uriBuilder);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uriBuilder);
    }
}
