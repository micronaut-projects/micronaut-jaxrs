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
package io.micronaut.jaxrs.common.multipart;

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.core.type.Argument;
import jakarta.ws.rs.core.EntityPart;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.List;

/**
 * The shared parts of the multipart reader and writer.
 */
final class Multipart {

    static final String BOUNDARY = "boundary";
    static final String CONTENT_DISPOSITION = "Content-Disposition";

    private Multipart() {
    }

    /**
     * @return Whether the type is a {@code List<EntityPart>}
     */
    static boolean isEntityPartList(Class<?> type, @Nullable Type genericType) {
        return type == List.class && genericType != null
            && JaxRsReflection.get().argument(genericType).getFirstTypeVariable().map(Argument::getType).orElse(null) == EntityPart.class;
    }

    /**
     * @return The value escaped in a quoted parameter: a quote or backslash as a quoted-pair, a line break
     * percent-encoded (RFC 7578 4.2) so it cannot end the header
     */
    static String quoted(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"', '\\' -> escaped.append('\\').append(c);
                case '\r' -> escaped.append("%0D");
                case '\n' -> escaped.append("%0A");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
