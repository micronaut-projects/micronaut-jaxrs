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
package io.micronaut.jaxrs.providers;

import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.Nullable;

/**
 * The XML media types of the standard providers (JAX-RS 4.2.4): {@code text/xml},
 * {@code application/xml} and {@code application/*+xml}.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
final class XmlMediaTypes {

    private XmlMediaTypes() {
    }

    /**
     * @param mediaType A media type, {@code null} for any
     * @return Whether it is an XML type, or a wildcard compatible with one
     */
    static boolean isXml(@Nullable MediaType mediaType) {
        if (mediaType == null || mediaType.isWildcardType()) {
            return true;
        }
        String type = mediaType.getType();
        String subtype = mediaType.getSubtype();
        if ("text".equals(type)) {
            return "xml".equals(subtype) || mediaType.isWildcardSubtype();
        }
        return "application".equals(type) && ("xml".equals(subtype) || subtype.endsWith("+xml") || mediaType.isWildcardSubtype());
    }
}
