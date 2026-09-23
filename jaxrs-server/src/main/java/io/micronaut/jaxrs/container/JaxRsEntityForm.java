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

    JaxRsEntityForm(String entity, Charset charset) {
        for (String pair : entity.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), charset);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), charset);
            fields.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
        }
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
