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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.List;

/**
 * The bean context implementation of {@link UriInfo}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
public final class JaxRsContextUriInfo implements UriInfo {

    @Override
    public String getPath() {
        return "";
    }

    @Override
    public String getPath(boolean decode) {
        return "";
    }

    @Override
    public List<PathSegment> getPathSegments() {
        return List.of();
    }

    @Override
    public List<PathSegment> getPathSegments(boolean decode) {
        return List.of();
    }

    @Override
    public URI getRequestUri() {
        return null;
    }

    @Override
    public UriBuilder getRequestUriBuilder() {
        return null;
    }

    @Override
    public URI getAbsolutePath() {
        return null;
    }

    @Override
    public UriBuilder getAbsolutePathBuilder() {
        return null;
    }

    @Override
    public URI getBaseUri() {
        return null;
    }

    @Override
    public UriBuilder getBaseUriBuilder() {
        return null;
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters() {
        return null;
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        return null;
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters() {
        return null;
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        return null;
    }

    @Override
    public List<String> getMatchedURIs() {
        return List.of();
    }

    @Override
    public List<String> getMatchedURIs(boolean decode) {
        return List.of();
    }

    @Override
    public List<Object> getMatchedResources() {
        return List.of();
    }

    @Override
    public URI resolve(URI uri) {
        return null;
    }

    @Override
    public URI relativize(URI uri) {
        return null;
    }
}
