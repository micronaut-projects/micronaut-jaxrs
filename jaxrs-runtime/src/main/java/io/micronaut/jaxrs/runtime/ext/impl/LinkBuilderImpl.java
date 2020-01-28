package io.micronaut.jaxrs.runtime.ext.impl;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.ArgumentUtils;

import javax.ws.rs.core.Link;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;


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
        ArgumentUtils.requireNonNull("uri", uri);
        uriBuilder = UriBuilder.fromUri(uri);
        return this;
    }

    @Override
    public Link.Builder uri(String uri) throws IllegalArgumentException {
        ArgumentUtils.requireNonNull("uri", uri);
        uriBuilder = UriBuilder.fromUri(uri);
        return this;
    }

    @Override
    public Link.Builder rel(String rel) {
        ArgumentUtils.requireNonNull("rel", rel);
        final String rels = this.map.get(Link.REL);
        param(Link.REL, rels == null ? rel : rels + " " + rel);
        return this;
    }

    @Override
    public Link.Builder title(String title) {
        ArgumentUtils.requireNonNull("title", title);
        param(Link.TITLE, title);
        return this;

    }

    @Override
    public Link.Builder type(String type) {
        ArgumentUtils.requireNonNull("type", type);
        param(Link.TYPE, type);
        return this;
    }

    @Override
    public Link.Builder param(String name, String value) throws IllegalArgumentException {
        ArgumentUtils.requireNonNull("name", name);
        ArgumentUtils.requireNonNull("value", value);
        this.map.put(name, value);
        return this;
    }

    @Override
    public Link build(Object... values) throws UriBuilderException {
        ArgumentUtils.requireNonNull("values", values);
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
        ArgumentUtils.requireNonNull("uri", uri);
        ArgumentUtils.requireNonNull("values", values);
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
