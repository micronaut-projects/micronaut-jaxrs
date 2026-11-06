package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The properties of the server {@code Configuration} are an immutable view (Configuration#getProperties).
 */
class ContextConfigurationPropertiesTest {

    @Test
    void propertiesOfTheApplicationCannotBeChangedThroughTheConfiguration() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "ContextConfigurationPropertiesTest"))) {
            Map<String, Object> properties = context.getBean(JaxRsContextConfiguration.class).getProperties();
            assertEquals("value", properties.get("name"));
            assertThrows(UnsupportedOperationException.class, () -> properties.put("other", "value"));
            assertEquals(1, context.getBean(PropertiesApplication.class).getProperties().size());
        }
    }

    @Requires(property = "spec.name", value = "ContextConfigurationPropertiesTest")
    @Singleton
    @io.micronaut.context.annotation.Primary
    public static class PropertiesApplication extends Application {
        private final Map<String, Object> properties = new HashMap<>(Map.of("name", "value"));

        @Override
        public Map<String, Object> getProperties() {
            return properties;
        }
    }
}
