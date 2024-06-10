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
package io.micronaut.jaxrs.runtime.ext.impl;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriBuilderException;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Forked from RESTEasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
final class LinkBuilderImpl implements Link.Builder {
    /**
     * A map for all the link parameters such as "rel", "type", etc.
     */
    protected final Map<String, String> map = new HashMap<>();
    private UriBuilder uriBuilder;
    private URI baseUri;

    @Override
    public Link.Builder link(Link link) {
        uriBuilder = UriBuilder.fromUri(link.getUri());
        this.map.clear();
        this.map.putAll(link.getParams());
        return this;
    }

    @Override
    public Link.Builder link(String link) {
        Link l = LinkImpl.valueOf(link);
        return link(l);
    }

    @Override
    public Link.Builder uriBuilder(UriBuilder uriBuilder) {
        this.uriBuilder = uriBuilder.clone();
        return this;
    }

    @Override
    public Link.Builder uri(URI uri) {
        JaxRsArgumentUtils.requireNonNull("uri", uri);
        uriBuilder = UriBuilder.fromUri(uri);
        return this;
    }

    @Override
    public Link.Builder uri(String uri) throws IllegalArgumentException {
        JaxRsArgumentUtils.requireNonNull("uri", uri);
        uriBuilder = UriBuilder.fromUri(uri);
        return this;
    }

    @Override
    public Link.Builder rel(String rel) {
        JaxRsArgumentUtils.requireNonNull("rel", rel);
        final String rels = this.map.get(Link.REL);
        param(Link.REL, rels == null ? rel : rels + " " + rel);
        return this;
    }

    @Override
    public Link.Builder title(String title) {
        JaxRsArgumentUtils.requireNonNull("title", title);
        param(Link.TITLE, title);
        return this;

    }

    @Override
    public Link.Builder type(String type) {
        JaxRsArgumentUtils.requireNonNull("type", type);
        param(Link.TYPE, type);
        return this;
    }

    @Override
    public Link.Builder param(String name, String value) throws IllegalArgumentException {
        JaxRsArgumentUtils.requireNonNull("name", name);
        JaxRsArgumentUtils.requireNonNull("value", value);
        this.map.put(name, value);
        return this;
    }

    @Override
    public Link build(Object... values) throws UriBuilderException {
        JaxRsArgumentUtils.requireNonNull("values", values);
        URI built = null;
        if (uriBuilder == null) {
            built = baseUri;
        } else {
            built = this.uriBuilder.build(values);
        }
        if (!built.isAbsolute() && baseUri != null) {
            built = baseUri.resolve(built);
        }
        return new LinkImpl(built, this.map);
    }

    @Override
    public Link buildRelativized(URI uri, Object... values) {
        JaxRsArgumentUtils.requireNonNull("uri", uri);
        JaxRsArgumentUtils.requireNonNull("values", values);
        URI built = uriBuilder.build(values);
        URI with = built;
        if (baseUri != null) {
            with = baseUri.resolve(built);
        }
        return new LinkImpl(uri.relativize(with), this.map);
    }

    @Override
    public Link.Builder baseUri(URI uri) {
        this.baseUri = uri;
        return this;
    }

    @Override
    public Link.Builder baseUri(String uri) {
        this.baseUri = URI.create(uri);
        return this;
    }
}
