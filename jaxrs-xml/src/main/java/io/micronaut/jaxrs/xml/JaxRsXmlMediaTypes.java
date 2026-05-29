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
package io.micronaut.jaxrs.xml;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * XML media type helpers shared by optional XML providers.
 */
@Internal
final class JaxRsXmlMediaTypes {
    private static final String XML_SUBTYPE = "xml";
    private static final String XML_SUBTYPE_SUFFIX = "+xml";

    private JaxRsXmlMediaTypes() {
    }

    static boolean isXml(@Nullable MediaType mediaType) {
        if (mediaType == null || mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            return true;
        }
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        return XML_SUBTYPE.equals(subtype) || subtype.endsWith(XML_SUBTYPE_SUFFIX);
    }

    static boolean isExplicitXml(@Nullable MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        return XML_SUBTYPE.equals(subtype) || subtype.endsWith(XML_SUBTYPE_SUFFIX);
    }
}
