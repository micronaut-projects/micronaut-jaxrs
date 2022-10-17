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
package io.micronaut.jaxrs.runtime.ext.convert;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.TypeConverterRegistrar;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;

import static jakarta.ws.rs.ext.RuntimeDelegate.getInstance;

/**
 * Registers JAX-RS converters.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
@Internal
public class JaxRsConverterRegistrar implements TypeConverterRegistrar {
    @Override
    public void register(ConversionService<?> conversionService) {
        conversionService.addConverter(MediaType.class, String.class, MediaType::toString);
        conversionService.addConverter(String.class, MediaType.class, MediaType::valueOf);
        conversionService.addConverter(EntityTag.class, String.class,
            entityTag -> getInstance().createHeaderDelegate(EntityTag.class).toString(entityTag));
        conversionService.addConverter(String.class, EntityTag.class,
            s -> getInstance().createHeaderDelegate(EntityTag.class).fromString(s));
        conversionService.addConverter(Link.class, String.class, Link::toString);
        conversionService.addConverter(String.class, Link.class, Link::valueOf);
        conversionService.addConverter(CacheControl.class, String.class,
            cacheControl -> getInstance().createHeaderDelegate(CacheControl.class).toString(cacheControl));
        conversionService.addConverter(String.class, CacheControl.class,
            s -> getInstance().createHeaderDelegate(CacheControl.class).fromString(s));
        conversionService.addConverter(Cookie.class, String.class, Cookie::getValue);
    }
}
