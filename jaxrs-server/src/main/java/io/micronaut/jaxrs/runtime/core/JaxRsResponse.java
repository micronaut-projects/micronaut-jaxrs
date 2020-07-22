/*
 * Copyright 2017-2020 original authors
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
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;

import javax.ws.rs.core.*;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.*;

/**
 * Adapter for JAX-RS and final Micronaut response.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
class JaxRsResponse extends Response {

    private final MutableHttpResponse<Object> response = HttpResponse.ok();

    /**
     * @return The Micronaut response object
     */
    public MutableHttpResponse<Object> getResponse() {
        return response;
    }

    @Override
    public int getStatus() {
        return response.status().getCode();
    }

    @Override
    public StatusType getStatusInfo() {
        return Status.valueOf(response.status().name());
    }

    @Override
    public Object getEntity() {
        return response.body();
    }

    @Override
    public <T> T readEntity(Class<T> entityType) {
        return response.getBody(entityType).orElse(null);
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType) {
        return null;
    }

    @Override
    public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
        return readEntity(entityType);
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        return readEntity(entityType);
    }

    @Override
    public boolean hasEntity() {
        return response.getBody().isPresent();
    }

    @Override
    public boolean bufferEntity() {
        return false;
    }

    @Override
    public void close() {

    }

    @Override
    public MediaType getMediaType() {
        return response.getContentType().map(mt -> MediaType.valueOf(mt.toString())).orElse(null);
    }

    @Override
    public Locale getLanguage() {
        return response.getLocale().orElse(null);
    }

    @Override
    public int getLength() {
        return (int) response.getContentLength();
    }

    @Override
    public Set<String> getAllowedMethods() {
        final List<String> allow = response.getHeaders().getAll(io.micronaut.http.HttpHeaders.ALLOW);
        return new HashSet<>(allow);
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        return null;
    }

    @Override
    public EntityTag getEntityTag() {
        return response.getHeaders()
                .getFirst(io.micronaut.http.HttpHeaders.ETAG)
                .map(EntityTag::valueOf).orElse(null);
    }

    @Override
    public Date getDate() {
        return null;
    }

    @Override
    public Date getLastModified() {
        return null;
    }

    @Override
    public URI getLocation() {
        return response.getHeaders()
                    .getFirst(io.micronaut.http.HttpHeaders.LOCATION)
                    .map(URI::create).orElse(null);
    }

    @Override
    public Set<Link> getLinks() {
        // TODO
        return Collections.emptySet();
    }

    @Override
    public boolean hasLink(String relation) {
        // TODO
        return false;
    }

    @Override
    public Link getLink(String relation) {
        // TODO
        return null;
    }

    @Override
    public Link.Builder getLinkBuilder(String relation) {
        // TODO
        return null;
    }

    @Override
    public MultivaluedMap<String, Object> getMetadata() {
        throw new UnsupportedOperationException("Unsupported deprecated method getMetadata()");
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        final MutableHttpHeaders headers = response.getHeaders();
        final Set<String> names = headers.names();
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>(names.size());

        for (String name : names) {
            final List<String> all = headers.getAll(name);
            map.addAll(name, all);
        }
        return map;
    }

    @Override
    public String getHeaderString(String name) {
        return response.getHeaders().getFirst(name).orElse(null);
    }
}
