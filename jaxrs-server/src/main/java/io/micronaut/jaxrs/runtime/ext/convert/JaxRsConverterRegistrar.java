package io.micronaut.jaxrs.runtime.ext.convert;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.TypeConverterRegistrar;

import javax.inject.Singleton;
import javax.ws.rs.core.*;

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
        conversionService.addConverter(EntityTag.class, String.class, EntityTag::toString);
        conversionService.addConverter(String.class, EntityTag.class, EntityTag::valueOf);
        conversionService.addConverter(Link.class, String.class, Link::toString);
        conversionService.addConverter(String.class, Link.class, Link::valueOf);
        conversionService.addConverter(CacheControl.class, String.class, CacheControl::toString);
        conversionService.addConverter(String.class, CacheControl.class, CacheControl::valueOf);
        conversionService.addConverter(Cookie.class, String.class, Cookie::getValue);
    }
}
