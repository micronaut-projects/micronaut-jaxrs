package example.extension;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without the micronaut-reflection module, a provider class an application lists that is not a
 * bean fails the startup with an error that says so, see {@link ApplicationListedProviderTest}.
 */
@EnabledIfSystemProperty(named = "micronaut.jaxrs.test.reflection", matches = "false")
class ApplicationListedProviderWithoutReflectionTest {

    @Test
    void listedProviderClassNeedsTheReflectionModule() {
        Exception e = assertThrows(Exception.class,
            () -> ApplicationContext.run(Map.of("spec.name", "ApplicationListedProviderTest")).close());
        Throwable cause = e;
        while (cause.getCause() != null && !cause.getMessage().contains("micronaut-reflection")) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains(ApplicationListedProviderTest.PlainWriter.class.getName())
            && cause.getMessage().contains("io.micronaut:micronaut-reflection"), cause.getMessage());
    }
}
