package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A context path configured with a trailing slash is still the prefix of the application path.
 */
@MicronautTest
@Property(name = "micronaut.server.context-path", value = "/ctx/")
class ContextPathTrailingSlashTest {

    @Inject
    ApplicationProvider applicationProvider;

    @Test
    void contextPathPrefixesTheApplicationPath() {
        assertEquals("/ctx", applicationProvider.getContextPath());
        assertEquals("/ctx/api", applicationProvider.getPath());
    }
}
