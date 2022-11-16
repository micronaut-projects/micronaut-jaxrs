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
import io.micronaut.core.convert.MutableConversionService;
import io.micronaut.core.convert.TypeConverterRegistrar;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.ext.RuntimeDelegate.HeaderDelegate;

/**
 * Registers JAX-RS converters.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public final class JaxRsConverterRegistrar implements TypeConverterRegistrar {

    @Override
    public void register(MutableConversionService conversionService) {
        RuntimeDelegate instance = RuntimeDelegate.getInstance();

        HeaderDelegate<EntityTag> entityTagHeaderDelegate = instance.createHeaderDelegate(EntityTag.class);
        HeaderDelegate<CacheControl> cacheControlHeaderDelegate = instance.createHeaderDelegate(CacheControl.class);

        conversionService.addConverter(MediaType.class, String.class, MediaType::toString);
        conversionService.addConverter(String.class, MediaType.class, MediaType::valueOf);
        conversionService.addConverter(EntityTag.class, String.class, entityTagHeaderDelegate::toString);
        conversionService.addConverter(String.class, EntityTag.class, entityTagHeaderDelegate::fromString);
        conversionService.addConverter(Link.class, String.class, Link::toString);
        conversionService.addConverter(String.class, Link.class, Link::valueOf);
        conversionService.addConverter(CacheControl.class, String.class, cacheControlHeaderDelegate::toString);
        conversionService.addConverter(String.class, CacheControl.class, cacheControlHeaderDelegate::fromString);
        conversionService.addConverter(Cookie.class, String.class, Cookie::getValue);
    }
}
