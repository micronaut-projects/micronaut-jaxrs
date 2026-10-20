package io.micronaut.jaxrs.common;

import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handlers selected for the media types of the requests are cached in bounded caches.
 */
@MicronautTest
class HandlerCacheTest {

    @Inject
    JaxRsContainerMessageBodyHandlerRegistry registry;

    @Test
    void theCachesOfTheMediaTypesOfTheRequestsAreBounded() {
        for (int i = 0; i < 2000; i++) {
            List<MediaType> mediaTypes = List.of(new MediaType("application/x-" + i));
            registry.findWriter(Argument.STRING, mediaTypes);
            registry.findReader(Argument.STRING, mediaTypes);
        }
        assertTrue(registry.cachedHandlers() <= 512, "cached " + registry.cachedHandlers());
    }
}
