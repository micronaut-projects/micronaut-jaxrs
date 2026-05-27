/*
 * Copyright 2017-2026 original authors
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

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaxRsResponseMetadataTest {

    @Test
    void builderResponseMetadataStoresObjectValues() {
        Object first = new Object();
        Vector<String> second = new Vector<>(List.of("value"));

        MultivaluedMap<String, Object> metadata = Response.ok().build().getMetadata();
        metadata.add("key", first);
        metadata.add("key", second);

        assertSame(first, metadata.getFirst("key"));
        assertEquals(List.of(first, second), metadata.get("key"));
    }

    @Test
    void builderResponseMetadataSupportsNullKeys() {
        Object value = new Object();

        MultivaluedMap<String, Object> metadata = Response.ok().build().getMetadata();
        metadata.add(null, value);

        assertSame(value, metadata.getFirst(null));
        assertEquals(List.of(value), metadata.get(null));
    }

    @Test
    void builderResponseMetadataFollowsMultivaluedMapOperations() {
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();

        MultivaluedMap<String, Object> metadata = Response.ok().build().getMetadata();
        metadata.addAll("key", first, second);
        metadata.addFirst("key", third);

        assertEquals(List.of(third, first, second), metadata.get("key"));
        metadata.get("key").clear();
        assertNull(metadata.getFirst("key"));
    }

    @Test
    void builderResponseMetadataComparesIgnoringValueOrder() {
        MultivaluedMap<String, Object> metadata = Response.ok().build().getMetadata();
        metadata.addAll("key", "a", "b", "a");

        MultivaluedMap<String, Object> other = Response.ok().build().getMetadata();
        other.addAll("key", "b", "a", "a");

        assertTrue(metadata.equalsIgnoreValueOrder(other));
        assertFalse(metadata.equals(other));
    }

    @Test
    void builderHeaderValuesRemainAvailableAsMetadataObjects() {
        Object value = new Object();

        MultivaluedMap<String, Object> metadata = Response.ok()
            .header("X-Test", value)
            .build()
            .getMetadata();

        assertSame(value, metadata.getFirst("X-Test"));
    }

    @Test
    void builderConvenienceHeadersRemainAvailableAsMetadata() {
        CacheControl cacheControl = new CacheControl();
        NewCookie cookie = new NewCookie("cookie", "value");
        Date expires = new Date(123456789L);
        Variant variant = new Variant(MediaType.TEXT_PLAIN_TYPE, Locale.CANADA_FRENCH, "gzip");

        MultivaluedMap<String, Object> metadata = Response.ok("body", "text/plain")
            .variant(variant)
            .cacheControl(cacheControl)
            .cookie(cookie)
            .expires(expires)
            .build()
            .getMetadata();

        assertSame(cacheControl, metadata.getFirst(HttpHeaders.CACHE_CONTROL));
        assertSame(cookie, metadata.getFirst(HttpHeaders.SET_COOKIE));
        assertEquals(MediaType.TEXT_PLAIN, metadata.getFirst(HttpHeaders.CONTENT_TYPE).toString());
        assertEquals("gzip", metadata.getFirst(HttpHeaders.CONTENT_ENCODING).toString());
        assertSame(expires, metadata.getFirst(HttpHeaders.EXPIRES));
        assertSame(Locale.CANADA_FRENCH, metadata.getFirst(HttpHeaders.CONTENT_LANGUAGE));
    }

    @Test
    void builderResponseMetadataUsesCaseInsensitiveHeaderNames() {
        MultivaluedMap<String, Object> metadata = Response.ok()
            .header("Content-type", "text/plain")
            .build()
            .getMetadata();

        assertTrue(metadata.containsKey(HttpHeaders.CONTENT_TYPE));
        assertEquals("text/plain", metadata.getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("text/plain", metadata.getFirst("CONTENT-TYPE"));
    }

    @Test
    void builderResponseDateAccessorsUseMetadataDateValues() {
        Date date = new Date(123456789L);

        Response response = Response.ok()
            .header(HttpHeaders.DATE, date)
            .lastModified(date)
            .build();

        assertEquals(date, response.getDate());
        assertEquals(date, response.getLastModified());
    }
}
