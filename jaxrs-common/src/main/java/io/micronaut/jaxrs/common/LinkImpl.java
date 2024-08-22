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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Forked from RESTEasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
class LinkImpl extends Link {
    private static final RuntimeDelegate.HeaderDelegate<Link> DELEGATE =
        RuntimeDelegate.getInstance().createHeaderDelegate(Link.class);

    /**
     * A map for all the link parameters such as "rel", "type", etc.
     */
    protected final Map<String, String> map;

    private final URI uri;

    /**
     * Default constructor.
     *
     * @param uri The URI
     * @param map The parameters
     */
    LinkImpl(final URI uri, final Map<String, String> map) {
        this.uri = uri;
        this.map = map.isEmpty() ? Collections.emptyMap() : Map.copyOf(map);
    }

    /**
     * Creates a link for the given value.
     *
     * @param value The value
     * @return The link
     */
    public static Link valueOf(String value) {
        return DELEGATE.fromString(value);
    }

    @Override
    public URI getUri() {
        return uri;
    }

    @Override
    public UriBuilder getUriBuilder() {
        return UriBuilder.fromUri(uri);
    }

    @Override
    public String getRel() {
        return map.get(REL);
    }

    @Override
    public List<String> getRels() {
        final String rels = map.get(REL);
        return rels == null ? Collections.emptyList() : Arrays.asList(rels.split(" +"));
    }

    @Override
    public String getTitle() {
        return map.get(TITLE);
    }

    @Override
    public String getType() {
        return map.get(TYPE);
    }

    @Override
    public Map<String, String> getParams() {
        return map;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other instanceof LinkImpl otherLink) {
            return uri.equals(otherLink.uri) && map.equals(otherLink.map);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 89 * hash + (this.uri != null ? this.uri.hashCode() : 0);
        hash = 89 * hash + (this.map != null ? this.map.hashCode() : 0);
        return hash;
    }

    @Override
    public String toString() {
        return DELEGATE.toString(this);
    }

}
