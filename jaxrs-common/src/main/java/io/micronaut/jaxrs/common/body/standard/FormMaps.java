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
package io.micronaut.jaxrs.common.body.standard;

import io.micronaut.http.form.FormConfiguration;
import io.micronaut.http.uri.QueryStringDecoder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The shared parts of the standard form providers.
 */
final class FormMaps {

    /**
     * The default limits of a form, for a client without a context.
     */
    static final FormConfiguration DEFAULT_CONFIGURATION = () -> 1024;

    private FormMaps() {
    }

    /**
     * @param form              The form
     * @param charset           The charset of the form
     * @param formConfiguration The limits of a form
     * @return Every value of each field
     */
    static Map<String, List<String>> decode(String form, Charset charset, FormConfiguration formConfiguration) {
        return new QueryStringDecoder(form, charset, false,
            formConfiguration.getMaxDecodedKeyValueParameters(),
            formConfiguration.isSemicolonIsNormalChar()).parameters();
    }

    /**
     * @param type The declared type
     * @return Whether the type is a multivalued map the readers can produce
     */
    static boolean isFormMap(Class<?> type) {
        return MultivaluedMap.class.isAssignableFrom(type) && type.isAssignableFrom(MultivaluedHashMap.class);
    }

    /**
     * @param mediaType The media type
     * @return Whether it is the one of a form, whatever its parameters
     */
    static boolean isForm(@Nullable MediaType mediaType) {
        return mediaType != null
            && MediaType.APPLICATION_FORM_URLENCODED_TYPE.getType().equalsIgnoreCase(mediaType.getType())
            && MediaType.APPLICATION_FORM_URLENCODED_TYPE.getSubtype().equalsIgnoreCase(mediaType.getSubtype());
    }

    /**
     * @param mediaType The media type
     * @return Its charset, else UTF-8
     */
    static Charset charset(@Nullable MediaType mediaType) {
        String charset = mediaType == null ? null : mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
        return charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset);
    }
}
