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
package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.form.FileUpload;
import io.micronaut.http.form.FormData;
import io.micronaut.http.form.FormFieldException;
import jakarta.ws.rs.BadRequestException;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The form of an {@code application/x-www-form-urlencoded} entity that a resource method reads as
 * its entity too, for its {@code @FormParam}s.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsEntityForm implements FormData {

    private final Map<String, List<String>> fields = new LinkedHashMap<>();
    // the values as they appear in the entity, see #raw()
    private final Map<String, List<String>> rawFields = new LinkedHashMap<>();

    /**
     * @param entity  The url-encoded form
     * @param charset Its charset
     * @throws BadRequestException if the form is not well percent-encoded
     */
    JaxRsEntityForm(String entity, Charset charset) {
        for (String pair : entity.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name;
            String value;
            try {
                name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), charset);
                String encoded = equals < 0 ? "" : pair.substring(equals + 1);
                // the decoded value, and the raw one for the raw view
                value = URLDecoder.decode(encoded, charset);
                rawFields.computeIfAbsent(name, n -> new ArrayList<>()).add(encoded);
            } catch (IllegalArgumentException e) {
                // a malformed percent-encoding is a malformed request
                throw new BadRequestException("Malformed form entity: " + e.getMessage(), e);
            }
            fields.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
        }
    }

    private JaxRsEntityForm(Map<String, List<String>> fields) {
        this.fields.putAll(fields);
        this.rawFields.putAll(fields);
    }

    /**
     * @return The form with the values as they appear in the entity, for {@code @Encoded} form parameters
     */
    @Override
    public FormData raw() {
        return new JaxRsEntityForm(rawFields);
    }

    @Override
    public Set<String> names() {
        return fields.keySet();
    }

    @Override
    public boolean contains(String name) {
        return fields.containsKey(name);
    }

    @Override
    public List<String> getValues(String name) {
        return fields.getOrDefault(name, List.of());
    }

    @Override
    public <T> T get(String name, Class<T> type) {
        return find(name, type).orElseThrow(() -> FormFieldException.missingField(name));
    }

    @Override
    public <T> Optional<T> find(String name, Class<T> type) {
        List<String> values = fields.get(name);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return ConversionService.SHARED.convert(values.get(0), type);
    }

    @Override
    public List<FileUpload> getFiles(String name) {
        // an url-encoded form has no files
        return List.of();
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        // nothing to release
    }
}
