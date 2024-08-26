/*
 * Copyright 2017-2024 original authors
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

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Variant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The variants list builder.
 * Originally forked from Resteasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @author Denis Stepanov
 * @since 4.7
 */
final class JaxRsVariantListBuilder extends Variant.VariantListBuilder {
    private final ArrayList<Variant> variants = new ArrayList<Variant>();
    private final ArrayList<Locale> currentLanguages = new ArrayList<Locale>();
    private final ArrayList<String> currentEncodings = new ArrayList<String>();
    private final ArrayList<MediaType> currentTypes = new ArrayList<MediaType>();

    public List<Variant> build() {
        add();
        ArrayList<Variant> copy = new ArrayList<Variant>(variants);
        variants.clear();
        currentLanguages.clear();
        currentEncodings.clear();
        currentTypes.clear();
        return copy;
    }

    public Variant.VariantListBuilder add() {
        int langSize = currentLanguages.size();
        int encodingSize = currentEncodings.size();
        int typeSize = currentTypes.size();

        int i = 0;

        if (langSize == 0 && encodingSize == 0 && typeSize == 0) {
            return this;
        }

        do {
            MediaType type = null;
            if (i < typeSize) {
                type = currentTypes.get(i);
            }
            int j = 0;
            do {
                String encoding = null;
                if (j < encodingSize) {
                    encoding = currentEncodings.get(j);
                }
                int k = 0;
                do {
                    Locale language = null;
                    if (k < langSize) {
                        language = currentLanguages.get(k);
                    }
                    variants.add(new Variant(type, language, encoding));
                    k++;
                } while (k < langSize);
                j++;
            } while (j < encodingSize);
            i++;
        } while (i < typeSize);

        currentLanguages.clear();
        currentEncodings.clear();
        currentTypes.clear();

        return this;
    }

    public Variant.VariantListBuilder languages(Locale... languages) {
        currentLanguages.addAll(Arrays.asList(languages));
        return this;
    }

    @Override
    public Variant.VariantListBuilder encodings(String... encodings) {
        currentEncodings.addAll(Arrays.asList(encodings));
        return this;
    }

    @Override
    public Variant.VariantListBuilder mediaTypes(MediaType... mediaTypes) {
        currentTypes.addAll(Arrays.asList(mediaTypes));
        return this;
    }
}
