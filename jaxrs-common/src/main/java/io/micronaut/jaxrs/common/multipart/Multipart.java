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
            && Argument.of(genericType).getFirstTypeVariable().map(Argument::getType).orElse(null) == EntityPart.class;
    }

    /**
     * @return The value of a parameter of a {@code Content-Disposition}, unquoted
     */
    static @Nullable String parameter(String disposition, String name) {
        for (String parameter : disposition.split(";")) {
            int equals = parameter.indexOf('=');
            if (equals > 0 && parameter.substring(0, equals).trim().equalsIgnoreCase(name)) {
                String value = parameter.substring(equals + 1).trim();
                if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                    value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
                }
                return value;
            }
        }
        return null;
    }

    /**
     * @return The value escaped in a quoted parameter
     */
    static String quoted(String value) {
        return value.replace("\"", "\\\"");
    }
}
