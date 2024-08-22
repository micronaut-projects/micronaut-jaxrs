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
package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.runtime.ext.bind.UriInfoImpl;
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
final class JaxRsContextUriInfo implements UriInfo {

    private final ApplicationPathProvider applicationPathProvider;

    JaxRsContextUriInfo(ApplicationPathProvider applicationPathProvider) {
        this.applicationPathProvider = applicationPathProvider;
    }

    private UriInfoImpl getUriInfo() {
        return new UriInfoImpl(ServerRequestContext.currentRequest().get(), applicationPathProvider.getPath());
    }

    @Override
    public String getPath() {
        return getUriInfo().getPath();
    }

    @Override
    public String getPath(boolean decode) {
        return getUriInfo().getPath(decode);

    }

    @Override
    public List<PathSegment> getPathSegments() {
        return getUriInfo().getPathSegments();
    }

    @Override
    public List<PathSegment> getPathSegments(boolean decode) {
        return getUriInfo().getPathSegments();
    }

    @Override
    public URI getRequestUri() {
        return getUriInfo().getRequestUri();
    }

    @Override
    public UriBuilder getRequestUriBuilder() {
        return getUriInfo().getRequestUriBuilder();
    }

    @Override
    public URI getAbsolutePath() {
        return getUriInfo().getAbsolutePath();
    }

    @Override
    public UriBuilder getAbsolutePathBuilder() {
        return getUriInfo().getAbsolutePathBuilder();
    }

    @Override
    public URI getBaseUri() {
        return getUriInfo().getBaseUri();
    }

    @Override
    public UriBuilder getBaseUriBuilder() {
        return getUriInfo().getBaseUriBuilder();
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters() {
        return getUriInfo().getPathParameters();
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        return getUriInfo().getPathParameters(decode);
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters() {
        return getUriInfo().getQueryParameters();
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        return getUriInfo().getQueryParameters(decode);
    }

    @Override
    public List<String> getMatchedURIs() {
        return getUriInfo().getMatchedURIs();
    }

//    @Override v4
    public String getMatchedResourceTemplate() {
        return "";
    }

    @Override
    public List<String> getMatchedURIs(boolean decode) {
        return getUriInfo().getMatchedURIs(decode);
    }

    @Override
    public List<Object> getMatchedResources() {
        return getUriInfo().getMatchedResources();
    }

    @Override
    public URI resolve(URI uri) {
        return getUriInfo().resolve(uri);
    }

    @Override
    public URI relativize(URI uri) {
        return getUriInfo().relativize(uri);
    }
}
